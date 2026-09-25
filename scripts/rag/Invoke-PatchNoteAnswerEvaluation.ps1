[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PlanPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9-]{1,80}$')]
    [string]$QuestionId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedPlanSha256,

    [Parameter(Mandatory = $true)]
    [uri]$Endpoint,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $false)]
    [string]$Locale = 'ko-KR'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-Sha256Hex([byte[]]$Bytes) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-RequiredPlanValue([hashtable]$Properties, [string]$Key) {
    if (-not $Properties.ContainsKey($Key) -or [string]::IsNullOrWhiteSpace([string]$Properties[$Key])) {
        throw "Approved plan property '$Key' is required."
    }
    return [string]$Properties[$Key]
}

function Read-Utf8Properties([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xef -and $bytes[1] -eq 0xbb -and $bytes[2] -eq 0xbf) {
        throw 'The approved plan must be UTF-8 without a BOM.'
    }
    $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
    $text = $utf8.GetString($bytes)
    $properties = @{}
    foreach ($line in ($text -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#') -or $line.StartsWith('!')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw 'The approved plan contains an invalid properties line.'
        }
        $key = $line.Substring(0, $separator).Trim()
        if ($properties.ContainsKey($key)) {
            throw "The approved plan repeats '$key'."
        }
        $properties[$key] = $line.Substring($separator + 1)
    }
    return @{ Bytes = $bytes; Properties = $properties }
}

$resolvedPlanPath = (Resolve-Path -LiteralPath $PlanPath -ErrorAction Stop).Path
$plan = Read-Utf8Properties $resolvedPlanPath
$planSha256 = Get-Sha256Hex $plan.Bytes
if ($planSha256 -ne $ExpectedPlanSha256.ToLowerInvariant()) {
    throw 'The approved plan SHA-256 does not match ExpectedPlanSha256.'
}

$countText = Get-RequiredPlanValue $plan.Properties 'question.count'
$count = 0
if (-not [int]::TryParse($countText, [ref]$count) -or $count -ne 6) {
    throw 'The approved plan must contain exactly six questions.'
}

$question = $null
for ($index = 1; $index -le $count; $index++) {
    $prefix = "question.$index."
    if ((Get-RequiredPlanValue $plan.Properties ($prefix + 'id')) -eq $QuestionId) {
        $question = @{
            Id = $QuestionId
            Query = Get-RequiredPlanValue $plan.Properties ($prefix + 'query')
            PatchVersion = Get-RequiredPlanValue $plan.Properties ($prefix + 'patchVersion')
            Locale = if ($plan.Properties.ContainsKey($prefix + 'locale')) { Get-RequiredPlanValue $plan.Properties ($prefix + 'locale') } else { $Locale }
        }
        break
    }
}
if ($null -eq $question) {
    throw "QuestionId '$QuestionId' is not in the approved plan."
}

$requestObject = [ordered]@{
    patchVersion = $question.PatchVersion
    locale = $question.Locale
    question = $question.Query
}
$json = $requestObject | ConvertTo-Json -Compress
Add-Type -AssemblyName System.Web.Extensions
$serializer = [System.Web.Script.Serialization.JavaScriptSerializer]::new()
$parsed = $serializer.DeserializeObject($json)
if ($parsed['patchVersion'] -cne $question.PatchVersion -or $parsed['locale'] -cne $question.Locale -or $parsed['question'] -cne $question.Query) {
    throw 'JSON serialization changed an approved request field.'
}

$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$bodyBytes = $utf8.GetBytes($json)
$questionBytes = $utf8.GetBytes($question.Query)
$bodySha256 = Get-Sha256Hex $bodyBytes
$questionSha256 = Get-Sha256Hex $questionBytes

[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$responsePath = Join-Path $OutputDirectory ($QuestionId + '.response.json')
$transmissionPath = Join-Path $OutputDirectory ($QuestionId + '.transmission.json')
if ([System.IO.File]::Exists($responsePath) -or [System.IO.File]::Exists($transmissionPath)) {
    throw 'Refusing to overwrite a prior client artifact for this question ID.'
}

$client = [System.Net.Http.HttpClient]::new()
$request = $null
$content = $null
try {
    $content = [System.Net.Http.ByteArrayContent]::new($bodyBytes)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Endpoint)
    $request.Headers.Add('X-Rag-Manual-Question-Id', $QuestionId)
    $request.Content = $content
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $responseBytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    [System.IO.File]::WriteAllBytes($responsePath, $responseBytes)
    $responseText = $utf8.GetString($responseBytes)
    $responseJson = $serializer.DeserializeObject($responseText)
    $transmission = [ordered]@{
        evaluationRunId = $RunId
        questionId = $QuestionId
        planSha256 = $planSha256
        questionSha256 = $questionSha256
        questionUtf8Bytes = $questionBytes.Length
        requestBodySha256 = $bodySha256
        requestBodyUtf8Bytes = $bodyBytes.Length
        responseStatus = [int]$response.StatusCode
        responseBodySha256 = Get-Sha256Hex $responseBytes
        responseContentType = [string]$response.Content.Headers.ContentType
        responseJsonParsed = ($null -ne $responseJson)
    }
    $transmissionJson = $transmission | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllBytes($transmissionPath, $utf8.GetBytes($transmissionJson))
    [PSCustomObject]$transmission
}
finally {
    if ($null -ne $request) { $request.Dispose() }
    if ($null -ne $content) { $content.Dispose() }
    $client.Dispose()
}
