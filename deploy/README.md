# Blue/Green Deployment

이 디렉터리는 EC2 단일 서버에서 `nginx + blue/green app container` 방식으로 무중단에 가깝게 배포하기 위한 파일을 담는다.

구성:
- `docker-compose.bluegreen.yml`
  - `proxy`: 외부 `8080` 포트를 받는 nginx
  - `app-blue`: 내부 `127.0.0.1:18080`
  - `app-green`: 내부 `127.0.0.1:18081`
- `nginx/conf.d/default.conf`
  - `/etc/nginx/runtime/active-upstream.conf`를 include 해서 현재 활성 색상을 프록시한다.
- `scripts/deploy-bluegreen.sh`
  - 비활성 색상에 새 이미지를 띄운 뒤 `/actuator/health` 통과 시 nginx upstream을 전환한다.

동작 순서:
1. 비활성 색상 컨테이너 시작
2. 내부 health check 확인
3. nginx upstream 전환 및 reload
4. 이전 색상 컨테이너 중지

주의:
- 기존 단일 컨테이너 `vlainter-app` 구조에서 처음 넘어올 때는 `proxy`가 `8080`을 잡기 위해 레거시 컨테이너를 정리한다.
- 이 첫 전환 때만 아주 짧은 포트 바인딩 공백이 생길 수 있다.
- `app-blue`, `app-green`은 `restart: unless-stopped`로 유지해 비활성 색상이 재부팅 후 자동 기동되지 않도록 한다.
- `proxy`는 `127.0.0.1:8080`에만 바인딩해 host nginx를 우회한 직접 접근을 막는다.
- 운영 배포에서는 `TRUSTED_PROXY_CIDRS`를 반드시 설정하고, 앱은 proxy가 주입하는 `X-Internal-Client-IP`만 클라이언트 IP로 신뢰한다.
- host nginx는 Cloudflare real IP를 복원한 뒤 backend proxy로 넘길 때 `proxy_set_header X-Internal-Client-IP $remote_addr;`를 명시해야 한다.
