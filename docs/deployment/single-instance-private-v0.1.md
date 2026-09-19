# 단일 인스턴스 비공개 배포 v0.1

## 목적과 한계

이 절차는 Spring Boot app, PostgreSQL, Redis를 한 EC2에서 Docker Compose로 실행하는 개인 검증용 배포다.
공개 서비스나 high availability 구성이 아니며, DB/Redis와 app은 인터넷에 직접 공개하지 않는다. 개발용
`docker-compose.yml`을 사용하거나 개발 volume을 재사용하지 않는다.

현재 async `AnalysisJob` command와 in-flight dedupe, Riot cooldown은 JVM-local이다. container를 정상 종료해도 실행 중
job의 재시작 복구, stale job 정리, 사용자 ownership, distributed scheduler/lock은 제공하지 않는다.

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

Compose는 필수값이 빠지면 시작 전에 실패한다. `RIOT_API_KEY`의 `@NotBlank` 계약은 유지한다. 기동 smoke에는 외부 호출을
만들지 않는 non-empty test key를 쓸 수 있지만, 실제 key와 test key 어느 쪽도 image·Git·test artifact에 넣지 않는다.

## 시작, health, SSM 접근

모든 명령은 배포 디렉터리에서 같은 env-file 쌍을 명시한다. Compose project와 volume 이름은 고정된 배포 전용 이름이므로
개발 `postgres-data`와 다르다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml config
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml up -d
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml ps
curl --fail --silent http://127.0.0.1:18080/actuator/health
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml logs --tail=200 app
```

app은 PostgreSQL health 이후 시작하고 startup에서 Flyway migration과 JPA mapping validation을 수행한다. `/actuator/health`만
노출하며 detail은 숨긴다. `env`, `configprops`, heap dump와 shutdown actuator endpoint는 활성화하지 않는다.

로컬 PC에서 app을 보려면 다음 port forwarding session을 유지한 뒤 `http://127.0.0.1:18080/actuator/health`에 접근한다.

```text
aws ssm start-session --target i-REPLACE_ME --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["18080"],"localPortNumber":["18080"]}' --region REPLACE_ME
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

backup은 app을 계속 실행한 상태에서도 가능하지만, restore 전에는 app을 멈춘다. backup file은 instance 밖의 암호화된 보존 위치와
retention 정책을 별도로 정한다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > lol-insight-YYYYMMDD.dump
```

다음 restore는 target DB의 객체를 변경·삭제할 수 있으므로, 올바른 backup과 대상 DB를 확인한 뒤에만 수행한다.

```text
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml stop app
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < lol-insight-YYYYMMDD.dump
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml start app
```

restore 뒤에는 app health와 Flyway history/schema compatibility를 검증한다. 현재 app image보다 미래 schema를 가진 backup을 과거 image와
조합하지 않는다.

## 비용과 종료 정리

EC2 실행 시간, EBS volume/snapshot, public IPv4 또는 Elastic IP 사용 방식, NAT gateway, image registry 전송·보관,
CloudWatch/SSM/S3 log와 backup 보관은 비용을 발생시킬 수 있다. 인스턴스 종료만으로 EBS·snapshot·Elastic IP·registry artifact가
자동 삭제된다고 가정하지 말고, 필요 없는 resource와 retained backup을 인벤토리 기준으로 명시적으로 정리한다.

AWS Budget의 notification은 비용/forecast를 알려 주는 기능이지 자동 비용 차단이 아니다. notification-only budget은 resource를
중지하거나 삭제하지 않으며 billing data와 알림에는 지연이 있을 수 있다. 자동 대응이 필요하면 별도의 Budget Action/approval과
resource lifecycle 절차를 설계·검증해야 한다. [AWS Budget의 알림 지연과 action 설명](https://docs.aws.amazon.com/cost-management/latest/userguide/bcm-lite-use-budget.html)을 확인한다.

Development/Personal Riot key는 개인 검증 용도와 Riot의 해당 key 정책 범위 안에서만 사용한다. 이 배포는 public launch를
승인하거나 service-scale API quota·사용자 인증·공개 endpoint 요구를 충족시키지 않는다. 공개 출시 전에는 Riot 정책·키 승인,
network/security, ownership/authorization, persistent job recovery와 multi-instance 운영을 별도 검토한다.
