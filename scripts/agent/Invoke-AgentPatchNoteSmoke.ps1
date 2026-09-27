[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ServerBaseUrl,
    [Parameter(Mandatory = $true)] [string] $PlanPath,
    [Parameter(Mandatory = $true)] [string] $ExpectedPlanSha256,
    [Parameter(Mandatory = $true)] [string] $ClientCaptureDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-Sha256([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Read-Utf8NoBom([string] $Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        throw 'The approved plan must be UTF-8 without BOM.'
    }
    return [Text.Encoding]::UTF8.GetString($bytes)
}

function Read-Properties([string] $Text) {
    $values = @{}
    foreach ($line in $Text -split "`r?`n") {
        if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
        $parts = $line.Split('=', 2)
        if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0])) { throw 'Invalid approved plan properties.' }
        $values[$parts[0].Trim()] = $parts[1]
    }
    return $values
}

$planBytes = [IO.File]::ReadAllBytes($PlanPath)
if ((Get-Sha256 $planBytes) -ne $ExpectedPlanSha256.ToLowerInvariant()) { throw 'Approved plan SHA-256 mismatch.' }
$plan = Read-Properties (Read-Utf8NoBom $PlanPath)
$required = 'runId','questionId','questionSha256','gameName','tagLine','path','patchVersion','locale','maxHttpRequests','maxResponsesRequests','maxToolExecutions','maxPatchNoteSearches','maxQueryEmbeddings','maxOpenAiAttempts'
foreach ($key in $required) { if ([string]::IsNullOrWhiteSpace($plan[$key])) { throw "Missing approved plan property: $key" } }
if ($plan.maxHttpRequests -ne '1' -or $plan.maxResponsesRequests -ne '3' -or $plan.maxToolExecutions -ne '2' -or $plan.maxPatchNoteSearches -ne '1' -or $plan.maxQueryEmbeddings -ne '1' -or $plan.maxOpenAiAttempts -ne '4') { throw 'Approved plan limits are not the single-smoke limits.' }

$question = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('MjUuMTAg7Yyo7LmY7JeQ7IScIOujsOujqCDqtoHqt7nquLAg7J6s7IKs7JqpIOuMgOq4sOyLnOqwhOydtCDslrTrlrvqsowg67CU64CM7JeI64qU7KeAIOqzteyLnSDtjKjsuZgg64W47Yq4IOq3vOqxsOuhnCDqsITri6jtnogg7JWM66Ck7KSYLg=='))
$questionBytes = [Text.Encoding]::UTF8.GetBytes($question)
if ((Get-Sha256 $questionBytes) -ne $plan.questionSha256.ToLowerInvariant()) { throw 'Fixed Korean question SHA-256 mismatch.' }
$path = "/api/v1/players/$($plan.gameName)/$($plan.tagLine)/agent-questions"
if ($path -ne $plan.path) { throw 'Approved endpoint path mismatch.' }
if ($plan.patchVersion -ne '25.10' -or $plan.locale -ne 'ko-KR') { throw 'Approved scope mismatch.' }

$body = @{ question = $question; knowledgeScope = @{ patchVersion = $plan.patchVersion; locale = $plan.locale } } | ConvertTo-Json -Depth 4 -Compress
# Reparse before transmission so the request body is a valid UTF-8 JSON representation of the fixed values.
$json = [Web.Script.Serialization.JavaScriptSerializer]::new()
$null = $json.DeserializeObject($body)
$bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)

[IO.Directory]::CreateDirectory($ClientCaptureDirectory) | Out-Null
$uri = $ServerBaseUrl.TrimEnd('/') + $path
$client = [Net.Http.HttpClient]::new()
try {
    $content = [Net.Http.ByteArrayContent]::new($bodyBytes)
    $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, $uri)
    $request.Headers.Add('X-Agent-Manual-Question-Id', $plan.questionId)
    $request.Content = $content
    # Exactly one SendAsync call: this script deliberately has no retry path.
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $responseBytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $responseText = [Text.Encoding]::UTF8.GetString($responseBytes)
    $null = $json.DeserializeObject($responseText)
    $capture = [ordered]@{
        runId = $plan.runId
        questionId = $plan.questionId
        requestPath = $path
        requestUtf8Bytes = $bodyBytes.Length
        requestSha256 = Get-Sha256 $bodyBytes
        responseStatus = [int]$response.StatusCode
        responseUtf8Bytes = $responseBytes.Length
        responseSha256 = Get-Sha256 $responseBytes
        responseContentType = [string]$response.Content.Headers.ContentType
    }
    [IO.File]::WriteAllText((Join-Path $ClientCaptureDirectory 'client-http.json'), ($capture | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    Write-Output ("Agent smoke HTTP status: {0}" -f [int]$response.StatusCode)
} finally {
    $client.Dispose()
}
