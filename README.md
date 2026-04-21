# 🎤 VlaInter

<div align="center">

![banner](https://dummyimage.com/1200x400/111827/ffffff&text=Vlainter+-+AI+Interview+Platform)

<h3>AI가 면접관이 되는 순간</h3>
<p>기술 면접부터 이력서 기반 개인화 면접, 학생 학습 흐름까지 연결하는 AI 인터뷰 플랫폼</p>

</div>

---

## 💡 Why VlaInter?

> "이 질문이 정말 내 경험을 보고 나온 걸까?"

많은 면접 준비 서비스는 단순한 질문 생성에서 멈춥니다.  
VlaInter는 실제 준비 흐름이 끊기지 않도록 질문 생성, 답변 평가, 문서 분석, 학습 복기까지 한 흐름으로 연결합니다.

- 기술 스택 기반 실전 면접
- 이력서/자기소개서/포트폴리오 기반 개인화 질문
- 학생 과목/자료 기반 요약, 시험, 오답노트
- AI 모범 답안 생성과 피드백
- 세션 저장 및 복기 기능

## ✨ Core Features

### 1️⃣ 기술 면접 모드

- 직무와 기술 스택 기반 질문 생성
- 답변 제출 후 AI 모범 답안과 피드백 제공
- 저장 질문과 질문 세트 관리
- 세션 결과와 이력 조회

### 2️⃣ 이력서 기반 개인화 면접

- 이력서, 자기소개서, 포트폴리오 업로드
- 문서 ingestion 및 OCR fallback 처리
- 경험 중심 맞춤 질문 생성
- 답변 평가와 문서 기반 모의면접 세션 관리

### 3️⃣ 학생 학습 모드

- 과목 생성과 학적 정보 기반 학습 진입
- 강의 자료 업로드와 유튜브 자료 요약
- 시험 세션 생성과 제출
- 오답노트, 요약 문서, 복습 흐름 제공

### 4️⃣ 운영 및 관리 기능

- 관리자 회원 관리
- 질문/카테고리/사이트 설정 운영
- 포인트 결제와 패치노트 관리
- 사용자 문의 및 리포트 접수

---

# VlaInter Backend

VlaInter 백엔드는 위 기능들을 실제로 제공하기 위한 Kotlin + Spring Boot 서버로, 인증, 사용자, 파일 업로드, 기술 면접, 문서 기반 모의면접, 학생 학습 모드, 결제, 운영 기능을 담당합니다.

## 주요 기능 소개

- 이메일 회원가입, 로그인, 카카오 로그인, 이메일 인증, 임시 비밀번호 발급
- `HttpOnly Cookie + JWT + Redis Session` 기반 인증 및 세션 갱신
- 기술 면접 세션 생성, 답변 제출, 결과 조회, 저장 질문/질문 세트 관리
- 이력서/자기소개서/포트폴리오 기반 문서 면접 질문 생성과 평가
- 사용자 파일 업로드, S3 저장, OCR fallback 기반 문서 처리
- 학생 모드 과목 관리, 강의자료 분석, 시험 세션, 오답노트
- 포인트 상품 조회, 결제 준비/확정, 환불, PortOne webhook 처리
- 관리자용 회원 관리, 질문/카테고리 운영, 사이트 설정, 패치노트 관리

## 기술 스택 소개

| 구분 | 사용 기술 |
| --- | --- |
| Language | Kotlin 1.9.25, Java 21 |
| Framework | Spring Boot 3.5.10, Spring Web, Spring Security, Spring Data JPA |
| Database | PostgreSQL, H2(test) |
| Cache / Session | Redis (Upstash 포함) |
| Storage | AWS S3 |
| AI / Document | Gemini, AWS Bedrock, PDFBox, Apache POI, Tesseract OCR |
| Auth | JWT, HttpOnly Cookie, Kakao OAuth |
| Docs / Ops | SpringDoc OpenAPI, Actuator, Docker |

## 프로젝트 구조

```text
src/main/kotlin/com/cw/vlainter
├── domain
│   ├── academic      # 대학/학과 검색
│   ├── auth          # 회원가입, 로그인, 카카오 OAuth, 이메일 인증
│   ├── interview     # 기술 면접, 문서 면접, 질문 세트, AI 평가
│   ├── payment       # 포인트 결제/환불, PortOne 연동
│   ├── site          # 사이트 설정, 패치노트
│   ├── student       # 학생 모드 과목/자료/시험/오답노트
│   ├── support       # 문의/리포트 접수
│   ├── user          # 프로필, 서비스 모드, 학적 정보, 관리자 회원 관리
│   └── userFile      # 사용자 파일 메타데이터와 S3 업로드
└── global
    ├── config        # Security, CORS, S3, Properties
    ├── exception     # 공통 예외 처리
    ├── mail          # 메일 발송
    ├── security      # JWT, 쿠키, Redis 세션, 필터
    ├── springDoc     # Swagger/OpenAPI 설정
    └── web           # 웹 공통 설정
```

## 빠른 시작

`git pull` 또는 클론 직후 아래 순서대로 진행하면 됩니다.

모노레포 루트에서 시작했다면 먼저 `cd vlainter_BE`로 이동한 뒤 아래 명령을 실행하세요.

### 1. 실행 전 준비

- Java 21
- PostgreSQL
- Redis 또는 Upstash Redis 연결 정보
- 선택 기능별 외부 서비스
  - Kakao OAuth
  - SMTP 메일 서버
  - AWS S3
  - Gemini / Bedrock
  - PortOne

### 2. 환경 변수 파일 준비

`.env.example`을 복사해 `.env`를 만듭니다.

```bash
cp .env.example .env
```

최소 부팅 기준으로는 아래 항목부터 먼저 채우는 것을 권장합니다.

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_ISSUER`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`
- `JWT_ACCESS_EXP_SECONDS`, `JWT_REFRESH_EXP_SECONDS`
- `COOKIE_*`
- `API_KEY_ENCRYPTION_SECRET`
- `UPSTASH_REDIS_URL`

주의:
- 기본 `JPA_DDL_AUTO`는 `validate`입니다.
- 로컬 DB에 스키마가 없으면 바로 부팅되지 않을 수 있으므로, 첫 실행 시에는 스키마를 준비하거나 `JPA_DDL_AUTO=update` 또는 `create`로 조정해 확인하세요.

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

기본 포트는 `8080`입니다.

### 4. 헬스 체크

```bash
curl http://localhost:8080/actuator/health
```

### 5. Swagger 확인

Swagger는 기본 비활성입니다. `.env`에 `DOCS_ENABLED=true`를 추가한 뒤 서버를 재시작하면 아래 경로를 확인할 수 있습니다.

- `http://localhost:8080/swagger-ui/index.html`
- `http://localhost:8080/v3/api-docs`

## 환경 변수 가이드

아래 표는 README 기준으로 자주 쓰는 항목만 추린 것입니다. 자세한 placeholder는 `.env.example`을 확인하세요.

| 범주 | 주요 변수 | 설명 |
| --- | --- | --- |
| DB | `DB_*`, `JPA_DDL_AUTO`, `JPA_SHOW_SQL` | PostgreSQL 연결과 JPA 동작 |
| Redis | `UPSTASH_REDIS_URL` | 로그인 세션 저장소 |
| JWT / Cookie | `JWT_*`, `COOKIE_*` | 쿠키 기반 인증 설정 |
| CORS / Redirect | `REDIRECT_ALLOWED_ORIGINS`, `CORS_ALLOWED_ORIGINS` | FE 연동 허용 origin |
| OAuth | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI` | 카카오 로그인 |
| Mail | `MAIL_*` | 이메일 인증, 임시 비밀번호 |
| Storage | `S3_BUCKET_NAME`, `AWS_REGION` | 사용자 파일 저장 |
| AI | `GEMINI_API_KEY`, `GEMINI_*`, `BEDROCK_*` | 질문 생성, 평가, 임베딩 |
| OCR | `OCR_*` | 문서 텍스트 추출 fallback |
| Payment | `PORTONE_*` | 포인트 결제/환불 |
| Support | `SUPPORT_DISCORD_WEBHOOK_URL` | 사용자 문의 알림 |
| Docs | `DOCS_ENABLED` | Swagger 노출 여부 |

## 핵심 도메인과 대표 API

| 도메인 | 대표 경로 | 설명 |
| --- | --- | --- |
| Auth | `/api/auth/*` | 회원가입, 로그인, 카카오 로그인, refresh, logout |
| User | `/api/users/*` | 내 정보, 서비스 모드, 학적 정보, Gemini API Key |
| User File | `/api/users/files*` | 파일 업로드/삭제, 프로필 이미지 조회 |
| Tech Interview | `/api/interview/tech/*` | 기술 면접 세션, 답변, 결과, 저장 질문 |
| Mock Interview | `/api/interview/mock/*` | 문서 기반 면접 세션, ingestion, 결과 |
| Question Set | `/api/interview/sets/*` | 질문 세트 생성/조회/수정 |
| Student | `/api/student/courses/*` | 학생 과목, 자료, 시험, 오답노트 |
| Payment | `/api/payments/*` | 포인트 상품, 결제 준비/확정, 환불 |
| Academic | `/api/academics/*` | 대학/학과 검색 |
| Admin | `/api/admin/*` | 관리자 회원/질문/사이트 설정 |
| Site / Support | `/api/site/*`, `/api/support/*` | 패치노트, 사이트 설정, 리포트 |

## 인증 / 보안 방식

- 프론트는 `Authorization` 헤더 대신 쿠키 기반 인증을 사용합니다.
- 액세스/리프레시 토큰은 `HttpOnly Cookie`로 관리됩니다.
- 로그인 세션의 서버 측 진실 원천은 Redis입니다.
- FE에서는 `credentials: include`로 요청해야 정상 동작합니다.
- CORS 허용 origin은 `app.cors.allowed-origins`로 제어합니다.
- 프록시 환경에서는 `TRUSTED_PROXY_CIDRS`, `X-Internal-Client-IP` 설정이 중요합니다.

## 파일 업로드 / 문서 분석 메모

- 현재 구현은 `presigned temp -> finalize` 구조가 아니라, 백엔드가 multipart 업로드를 받아 S3로 저장하는 방식입니다.
- 업로드한 파일은 문서 기반 면접, 학생 모드 자료 분석, OCR fallback 흐름에서 재사용됩니다.
- OCR 관련 기능을 검증하려면 런타임에 `tesseract`가 필요합니다.
- Docker 이미지는 `tesseract-ocr`, `tesseract-ocr-kor`를 포함합니다.

## 로컬 검증 명령

```bash
./gradlew test
./gradlew build
```

권장 확인 순서:

1. `./gradlew test`
2. `./gradlew bootRun`
3. `curl http://localhost:8080/actuator/health`
4. 필요 시 `DOCS_ENABLED=true`로 Swagger 확인

## 운영 / 배포 개요

- Docker 기반으로 배포합니다.
- `deploy/` 디렉터리에는 단일 EC2 서버 기준 blue/green 배포 구성이 포함되어 있습니다.
- 상세 배포 구조는 [deploy/README.md](./deploy/README.md)를 확인하세요.

## 트러블슈팅

### 앱이 바로 뜨지 않고 DB 관련 오류가 납니다

- 기본값이 `JPA_DDL_AUTO=validate`인지 확인하세요.
- 로컬 DB 스키마가 없다면 `update` 또는 `create`로 임시 조정해 부팅을 확인하세요.

### 로그인/refresh가 동작하지 않습니다

- Redis 연결(`UPSTASH_REDIS_URL`)이 올바른지 확인하세요.
- 쿠키 관련 값(`COOKIE_DOMAIN`, `COOKIE_SECURE`, `COOKIE_SAME_SITE`)이 로컬 환경과 맞는지 확인하세요.

### Swagger가 열리지 않습니다

- `DOCS_ENABLED=true`인지 확인하세요.
- 서버 재시작 후 `swagger-ui/index.html` 경로로 접속하세요.

### 파일 업로드나 문서 분석이 실패합니다

- S3 관련 설정과 버킷 권한을 확인하세요.
- OCR이 필요한 문서는 `tesseract` 설치 여부를 확인하세요.
- AI 평가 흐름은 `GEMINI_API_KEY` 또는 Bedrock 설정이 필요할 수 있습니다.

## 참고

- 운영 배포 문서: [deploy/README.md](./deploy/README.md)
- 테스트 코드는 `src/test/kotlin` 아래에 도메인별로 구성되어 있습니다.
