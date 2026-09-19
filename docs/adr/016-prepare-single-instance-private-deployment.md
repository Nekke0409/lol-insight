# ADR-016: Single-instance Private Deployment v0.1 준비

상태: Accepted

## 배경

개발용 `docker-compose.yml`은 로컬 PostgreSQL/Redis 포트를 host에 게시하고, 로컬 기본 DB와 volume을 사용한다.
이는 개발에는 적합하지만 한 대의 EC2에서 개인 검증을 할 때 데이터·포트·secret 경계를 제공하지 않는다. 현재 서비스는
PostgreSQL/Flyway와 Redis에 의존하며, bounded executor와 automation/replenishment의 일부 state는 단일 JVM에 있다.

## 결정

JDK 21 Gradle Wrapper로 bootJar를 build하고 JRE 21 non-root runtime에서 직접 Java process를 실행하는 multi-stage
`Dockerfile`을 둔다. image build는 test를 실행하지 않고, CI 및 별도 Gradle regression 검증과 역할을 분리한다.

개발 Compose를 변경하지 않고 `compose.deploy.yaml`을 새로 둔다. 이 파일은 app, postgres, redis만 포함하며,
PostgreSQL의 명시적인 배포 전용 named volume을 사용한다. DB/Redis host port는 게시하지 않고 app만 loopback으로 bind한다.
배포 image는 `latest`가 아닌 versioned tag 또는 immutable digest로 전달한다.

일반 runtime parameter는 `deploy.env`, secret은 `deploy.secrets.env`로 분리하고 둘 다 Git에서 제외한다. Compose interpolation은
필수값 누락 시 시작 전에 이해 가능한 오류를 내며, secret은 Docker build ARG/ENV로 전달하지 않는다. `deploy` profile은
health endpoint만 노출하고, detail을 숨기며, automation scheduler·bootstrap runner·benchmark scheduler·run-once runner를
명시적으로 끈다.

단일 EC2의 비공개 접근은 inbound public port 대신 AWS Systems Manager Session Manager port forwarding을 우선한다.
인스턴스 생성, IAM/SSM 설정, image registry 선택과 image push는 이 결정의 구현 범위가 아니다.

## 결과와 Trade-off

- local Compose, 개발 DB, benchmark corpus와 독립된 시작·migration·stop·restart 검증 경로가 생긴다.
- PostgreSQL data는 container 재생성 뒤에도 volume에 남지만, Redis cache는 의도적으로 영속화하지 않는다.
- graceful shutdown은 Spring lifecycle 종료를 요청할 뿐, 실행 중/대기 중 in-memory `AnalysisJob`의 crash recovery나
  exactly-once dispatch를 보장하지 않는다.
- 이 구조는 한 JVM만 전제한다. multi-instance 운영 전에는 persistent queue, stale-job recovery, ownership,
  distributed scheduler/claim/lock/dedupe/rate-limit 정책을 별도 ADR로 결정해야 한다.
- EC2/EBS/공인 IPv4 또는 Elastic IP/NAT/image registry/log storage 등은 비용을 낼 수 있다. AWS Budget notification은
  알림일 뿐 자동 비용 차단이 아니며, 실제 중지/삭제는 명시적으로 설계·실행해야 한다.

## 검토한 대안

### 개발용 Compose를 그대로 사용

개발 DB·포트·volume과 배포 검증이 섞이고, 기존 benchmark corpus를 보호할 수 없다.

### 초기부터 RDS/ECS/EKS 또는 Kubernetes 사용

개인 비공개 검증에 필요한 운영 범위를 크게 넘기고, 현재 single-instance 제약을 해결하지 않는 추가 복잡도와 비용을 만든다.

### public HTTP endpoint 또는 SSH를 기본 접근 경로로 사용

현재 검증 범위에는 불필요한 외부 노출과 network/security-group 관리가 생긴다. Session Manager port forwarding이
개인 접근 목적에 더 작은 경계다.
