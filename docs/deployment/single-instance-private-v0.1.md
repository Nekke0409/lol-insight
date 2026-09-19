# 단일 인스턴스 비공개 배포 v0.1

## 목적과 한계

이 절차는 Spring Boot app, PostgreSQL, Redis를 한 EC2에서 Docker Compose로 실행하는 개인 검증용 배포다.
공개 서비스나 high availability 구성이 아니며, DB/Redis와 app은 인터넷에 직접 공개하지 않는다. 개발용
`docker-compose.yml`을 사용하거나 개발 volume을 재사용하지 않는다.

현재 async `AnalysisJob` command와 in-flight dedupe, Riot cooldown은 JVM-local이다. container를 정상 종료해도 실행 중
job의 재시작 복구, stale job 정리, 사용자 ownership, distributed scheduler/lock은 제공하지 않는다.

## 현재 진행 상태와 재개 조건

2026-09-19 기준으로 AWS 비공개 배포와 Free Tier 확인은 **보류**한다. 배포 설계를 취소한 것이 아니라, 먼저 기존 AI
Automation의 로컬 실행·결과 확인 경험을 완성하고 Tool 기반 AI Agent를 학습·구현한 뒤에 이 절차를 재개한다. 이 절은 이전
검증과 현재 미결정 사항을 보존하기 위한 기록이며, 이번 작업에서 AWS CLI/Session Manager plugin 설치, 로그인, 계정·권한·region
확인, resource 생성·변경·삭제, image push, secret 등록, 비용 산정은 수행하지 않았다.

### 이전 작업에서 확인·준비한 범위

다음은 현재 source와 이전 로컬 검증 기록으로 확인되는 준비 범위다. 이번 보류 작업에서 다시 실행한 결과가 아니다.

- Java 21 multi-stage Docker image, non-root runtime, `exec` Java entrypoint
- 개발 Compose와 분리된 standalone `compose.deploy.yaml`, app loopback bind, PostgreSQL/Redis host port 미게시,
  배포 전용 PostgreSQL named volume
- `deploy` profile의 Flyway/JPA validation·health·graceful stop/start 설정과 automation/replenishment opt-in 기본 비활성화
- `config --quiet` 사용과 secret 전체 출력 방지, 운영 DB를 덮어쓰지 않는 격리 restore 시험 절차, SSM port forwarding 안내
- linux/amd64 image preflight build

이전 검증에 사용한 임시 image·container·volume은 정리 대상이었으며, 그 상태를 현재 배포 가능 여부의 근거로 사용하지 않는다.
반면 `Dockerfile`, `compose.deploy.yaml`, profile 및 env example, 이 runbook 같은 배포 source는 저장소에 보존되어 있다.
현재 로컬에 image가 없다는 이유로 이번 작업에서 다시 build하지 않는다.

### 아직 검증하지 않은 범위와 비용 제약

실제 EC2 배포, AWS 환경에서의 SSM 접근, host 재부팅 뒤 복귀, AWS 환경의 backup/restore 실습은 아직 하지 않았다. 직전
확인 당시 local AWS CLI와 Session Manager plugin이 없었고 account·region·권한도 확인하지 못했다. 이는 영구적인 현재
상태를 뜻하지 않으므로 재개 시 다시 확인한다. 이 작업 흐름에서 AWS resource 생성, image push, secret 등록을 하지 않았으며,
계정 전체에 기존 resource가 없다고 추정하지 않는다.

사용자는 AWS 운영비를 별도로 지출할 계획이 없다. 실제 account에 적용되는 무료 혜택과 credit 범위에서만 배포 실습을 검토한다.
계정 생성 시점, `FREE`/`PAID` plan, 남은 credit·만료일, 무료 사용량과 기존 resource 사용량은 아직 확인되지 않았다. 따라서
이 구성이 무료이거나 현금 지출이 0원이라고 보장하는 확정 배포안은 없다. 과거의 `t3.medium`, 월 `$50` budget, ECR/S3 제안은
승인된 실행안이 아니다. 비용 알림은 실제 과금 차단과 다르며, 정책·가격은 재개 시 최신 AWS 공식 문서와 실제 account 상태로
다시 판단한다.

### 재개 체크리스트

1. 당시 최신 application code와 배포 source의 차이를 확인한다.
2. account의 무료 조건, 잔여 credit, 만료일, 현재 사용량과 기존 resource를 read-only로 확인한다.
3. 전체 resource의 비용과 정리 계획을 다시 평가한다.
4. 필요한 로컬 배포 회귀 검증을 수행한다. Automation/Agent 변경 뒤에는 이전 image와 과거 검증 결과를 최신 배포 승인 근거로
   사용하지 않는다.
5. 생성 범위와 잠재 비용에 대해 사용자 승인을 받은 뒤 실제 비공개 배포와 운영 검증을 진행한다.

## 사전 결정과 SSM 조건

인스턴스 type, region/AZ, EBS 크기·암호화·backup 보존, image 전달 방식(registry 또는 `docker load`), OS/patch 정책,
월 예산과 log 보존 기간은 운영자가 정한다. 이 문서는 AWS resource를 만들거나 image를 push하지 않는다.

SSM 접근을 위해 EC2는 SSM Agent가 실행 중인 managed node여야 하고, instance profile 또는 account-level configuration으로
`AmazonSSMManagedInstanceCore` 또는 동등한 최소 권한을 받아야 한다. 개인 operator에게도 대상 instance와
`AWS-StartPortForwardingSession` document에 대한 `ssm:StartSession` 권한을 최소 범위로 준다. AWS CLI를 쓰는 local machine에는
Session Manager plugin이 필요하다. [AWS의 Session Manager instance permission 안내](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-getting-started-instance-profile.html)와
[CLI port forwarding 예시](https://docs.aws.amazon.com/systems-manager/latest/userguide/example_ssm_StartSession_section.html)를 따른다.

보안 그룹에는 SSH, 18080, 5432, 6379 inbound rule을 만들지 않는다. SSM Agent는 inbound listener 없이 instance가 outbound
HTTPS로 AWS endpoint에 연결한다. 따라서 인터넷 egress 또는 해당 region의 SSM/SSM Messages VPC endpoint와 DNS가 필요하다.
[AWS의 SSM network 요구사항](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html)을 확인한다. app이 실제 Riot/OpenAI를 쓸 때는 각 provider로의 outbound HTTPS도 별도로 허용해야 한다.

## 이미지와 설정 준비

배포할 source revision에서 식별 가능한 tag를 만든다. `latest`는 사용하지 않는다. registry를 선택했다면 그 tag 또는 digest를
pull해 두고, registry를 쓰지 않는다면 같은 image를 안전한 artifact 전달 경로로 EC2에 가져와 `docker load`한다.

```text
docker build --tag lol-insight:0.1.0 .
# Registry delivery: docker pull REGISTRY/LOL_INSIGHT@sha256:REPLACE_ME
# File delivery: docker image save lol-insight:0.1.0 | gzip > lol-insight-0.1.0.tar.gz
# On EC2 after the file arrives: gzip -dc lol-insight-0.1.0.tar.gz | docker image load
```

EC2의 배포 디렉터리에는 최소 `compose.deploy.yaml`, `deploy.env`, `deploy.secrets.env`가 있어야 한다. 예시를 복사한 뒤
권한을 제한하고 실제 값을 채운다.

```text
cp deploy.env.example deploy.env
cp deploy.secrets.env.example deploy.secrets.env
chmod 600 deploy.secrets.env
```

`deploy.env`에는 다음 일반 값만 둔다.

```text
DEPLOY_APP_IMAGE=lol-insight:0.1.0
APP_HOST_PORT=18080
POSTGRES_DB=lol_insight_deploy
POSTGRES_USER=lol_insight_deploy
```

`deploy.secrets.env`에는 고유한 `POSTGRES_PASSWORD`, `RIOT_API_KEY`, `OPENAI_API_KEY`를 둔다. 파일은 Git, image build context,
container image에 포함되지 않는다. key 교체 때는 새 값을 이 파일에 기록하고 app을 recreate한다. PostgreSQL password를 바꾸려면
DB role password도 별도 SQL로 변경한 후 파일 값을 맞춰야 하며, env file만 바꿔서는 초기화가 끝난 DB의 password가 바뀌지 않는다.

`deploy.secrets.env`는 배포를 수행하는 OS 계정이 소유하고 그 계정만 읽을 수 있어야 한다. 다음 확인은 소유자·권한과 파일명만
출력하며 secret 내용은 출력하지 않는다.

```text
stat -c '%U %G %a %n' deploy.secrets.env
```

Compose는 필수값이 빠지면 시작 전에 실패한다. `RIOT_API_KEY`의 `@NotBlank` 계약은 유지한다. 기동 smoke에는 외부 호출을
만들지 않는 non-empty test key를 쓸 수 있지만, 실제 key와 test key 어느 쪽도 image·Git·test artifact에 넣지 않는다.

## 시작, health, SSM 접근

모든 명령은 배포 디렉터리에서 같은 env-file 쌍을 명시한다. Compose project와 volume 이름은 고정된 배포 전용 이름이므로
개발 `postgres-data`와 다르다. 실제 secret을 주입한 뒤에는 `docker compose ... config`처럼 resolved configuration 전체를
출력하는 명령을 사용하지 않는다. `env`, `docker inspect`의 전체 `Env`, `deploy.secrets.env` 내용도 출력하지 않는다.
대신 `config --quiet`으로 interpolation과 형식만 검증하고, 상태·image·opt-in flag는 아래처럼 필요한 비민감 필드만 선택해서
확인한다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml config --quiet
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml up -d
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml ps
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml images
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T app sh -c 'printf "automation=%s bootstrap=%s replenishment=%s run_once=%s\\n" "$ANALYSIS_AUTOMATION_ENABLED" "$ANALYSIS_AUTOMATION_BOOTSTRAP_ENABLED" "$BENCHMARK_REPLENISHMENT_ENABLED" "$RUN_BENCHMARK_REPLENISHMENT_ONCE"'
curl --fail --silent http://127.0.0.1:18080/actuator/health
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml logs --tail=200 app
```

app은 PostgreSQL health 이후 시작하고 startup에서 Flyway migration과 JPA mapping validation을 수행한다. `/actuator/health`만
노출하며 detail은 숨긴다. `env`, `configprops`, heap dump와 shutdown actuator endpoint는 활성화하지 않는다.

로컬 PC에서 app을 보려면 다음 port forwarding session을 유지한 뒤 `http://127.0.0.1:28080/actuator/health`에 접근한다.
`28080`이 이미 사용 중이면 개발·다른 배포 포트와 겹치지 않는 다른 local port를 고르고, 그 값만 바꾼다.

```text
aws ssm start-session --target i-REPLACE_ME --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["18080"],"localPortNumber":["28080"]}' --region REPLACE_ME
```

## 정상 종료, 재기동, 재배포

`stop`은 app에 SIGTERM을 전달한다. Spring은 graceful shutdown을 요청하고 최대 30초 동안 phase 종료를 기다린다. Compose의
app stop grace period는 45초다. 이 결과를 실행 중 Job 복구가 완료됐다는 증거로 해석하지 않는다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml stop app
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml start app
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml up -d --force-recreate app
```

재배포 전에는 새 image를 전달하고 `DEPLOY_APP_IMAGE`만 새 versioned tag 또는 digest로 바꾼다. app log, `ps`, health를 확인한다.
Flyway migration은 forward-only다. 이전 app image로 되돌리기 전에 새 schema가 그 이미지와 호환되는지 확인한다. 호환성이 없다면
image rollback만으로 DB rollback을 시도하지 말고, backup/restore와 별도 migration 복구 계획을 사용한다.

`docker compose down -v`는 배포 PostgreSQL volume까지 지우므로 일반 종료·재배포 절차에 사용하지 않는다.

## DB backup과 restore

backup은 app을 계속 실행한 상태에서도 가능하지만, backup file은 instance 밖의 암호화된 보존 위치와 retention 정책을 별도로
정한다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > lol-insight-YYYYMMDD.dump
```

복원 검증은 운영 DB나 `lol-insight-deploy-postgres-data` volume을 덮어쓰지 않는다. 아래처럼 새로 정한 임시 DB에 복원한다.
`RESTORE_TEST_DB`에는 운영 DB와 다른, 소문자·숫자·underscore만 쓰는 이름을 넣는다. 생성 전 같은 이름의 DB가 없음을 확인하고,
마지막 `dropdb`는 이 복원 시험 DB에만 실행한다. 최초 배포 DB가 비어 있다면 이 절차는 schema와 Flyway history의 복원을
검증하는 것이며 업무 데이터 복원 검증과는 다르다.

```text
RESTORE_TEST_DB=lol_insight_restore_check_YYYYMMDD
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T -e "RESTORE_TEST_DB=$RESTORE_TEST_DB" postgres sh -c 'createdb -U "$POSTGRES_USER" "$RESTORE_TEST_DB"'
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T -e "RESTORE_TEST_DB=$RESTORE_TEST_DB" postgres sh -c 'pg_restore --exit-on-error -U "$POSTGRES_USER" -d "$RESTORE_TEST_DB" --no-owner' < lol-insight-YYYYMMDD.dump
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T -e "RESTORE_TEST_DB=$RESTORE_TEST_DB" postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$RESTORE_TEST_DB" -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T -e "RESTORE_TEST_DB=$RESTORE_TEST_DB" postgres sh -c 'dropdb -U "$POSTGRES_USER" "$RESTORE_TEST_DB"'
```

실제 장애 복구로 운영 DB를 복원해야 한다면 app을 먼저 멈추고, 올바른 backup과 대상 DB를 별도로 확인한 뒤에만
`pg_restore --clean --if-exists`를 사용한다. 이는 정기 복원 시험 절차가 아니다. 어느 경우에도 현재 app image보다 미래 schema를
가진 backup을 과거 image와 조합하지 않는다.

## 비용과 종료 정리

### Free Tier 사전 확인

Free Tier 적용 여부, 사용 가능한 instance type, credit 적용 대상과 만료일은 account 생성 시점·plan·현재 사용량에 따라 달라진다.
이 runbook은 이를 자동으로 무료 또는 현금 청구 없음으로 가정하지 않는다. AWS resource를 만들기 전 운영자는 Billing console 또는
권한이 있는 read-only Free Tier API로 account plan(`FREE`/`PAID`)과 상태, Free plan 종료일, 남은 credit·만료일·적용 대상,
현재 Free Tier 사용량과 기존 resource를 확인한다. 확인할 수 없거나 `PAID` plan에서 credit 잔액만 확인된 경우에는 현금 청구
위험이 없는 배포로 해석하지 않고 배포를 보류한다.

Free plan은 사용량 한도나 credit이 소진되면 종료될 수 있으며, 종료 뒤 account와 resource에 접근하지 못할 수 있다. 따라서
backup을 account 밖으로 가져올 시점과 retained resource 정리 시점을 plan 종료일보다 앞서 정한다. Free Tier usage alert 또는
notification-only budget은 사용량을 알릴 뿐 resource를 중지·삭제하거나 현금 청구를 차단하지 않는다.

EC2 실행 시간, EBS volume/snapshot, public IPv4 또는 Elastic IP 사용 방식, NAT gateway, image registry 전송·보관,
CloudWatch/SSM/S3 log와 backup 보관은 비용을 발생시킬 수 있다. 인스턴스 종료만으로 EBS·snapshot·Elastic IP·registry artifact가
자동 삭제된다고 가정하지 말고, 필요 없는 resource와 retained backup을 인벤토리 기준으로 명시적으로 정리한다.

AWS Budget의 notification은 비용/forecast를 알려 주는 기능이지 자동 비용 차단이 아니다. notification-only budget은 resource를
중지하거나 삭제하지 않으며 billing data와 알림에는 지연이 있을 수 있다. 자동 대응이 필요하면 별도의 Budget Action/approval과
resource lifecycle 절차를 설계·검증해야 한다. [AWS Budget의 알림 지연과 action 설명](https://docs.aws.amazon.com/cost-management/latest/userguide/bcm-lite-use-budget.html)을 확인한다.

Development/Personal Riot key는 개인 검증 용도와 Riot의 해당 key 정책 범위 안에서만 사용한다. 이 배포는 public launch를
승인하거나 service-scale API quota·사용자 인증·공개 endpoint 요구를 충족시키지 않는다. 공개 출시 전에는 Riot 정책·키 승인,
network/security, ownership/authorization, persistent job recovery와 multi-instance 운영을 별도 검토한다.
