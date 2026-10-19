/**
 * AcademyCraft 인증 헬퍼 (정식 로그인).
 *
 * - Access Token: 메모리 변수에만 보관 (XSS 시 localStorage 탈취 방지).
 *   페이지 이동 시에도 유지하려면 sessionStorage 사용을 권장하지 않음 —
 *   대신 Refresh 쿠키(HttpOnly)로 /api/rea/auth/refresh 호출해 재발급.
 * - Refresh Token: HttpOnly + SameSite=Strict 쿠키 (서버가 Set-Cookie, JS 접근 불가).
 * - 모든 API 호출에 Authorization: Bearer 헤더 자동 첨부.
 *
 * 사용법:
 *   <script src="../components/auth.js"></script>
 *   <script>
 *     // 로그인 후:
 *     await Auth.login(loginId, password); // 성공 시 대시보드로 이동
 *     // API 호출:
 *     const res = await Auth.api(`/api/rea/${academyId}/students`);
 *   </script>
 */
const Auth = (() => {
  const common = window.ACADEMYCRAFT_COMMON_AUTH === true;
  let commonContext = null;
  let commonPending = null;
  function commonLogin() { location.href = '/?login=required&returnUrl=%2Facademycraft'; }
  async function context() {
    if (!commonPending) commonPending = fetch('/academycraft/api/rea/integration/context', {credentials:'same-origin',cache:'no-store'})
      .then(async res => { if (!res.ok) throw new Error('학원 연결 또는 권한이 없습니다.'); return res.json(); })
      .then(data => { commonContext=data; return data; }).finally(() => { commonPending=null; });
    return commonPending;
  }
  let accessToken = null;
  let refreshPending = null;
  let tokenExpiry = 0; // 만료 시각 (ms)

  async function login(loginId, password) {
    if (common) { commonLogin(); return; }
    const res = await fetch('/api/rea/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginId, password }),
      credentials: 'same-origin', // Refresh 쿠키 수신
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.message || '로그인에 실패했습니다.');
    }
    const data = await res.json();
    accessToken = data.accessToken;
    tokenExpiry = Date.now() + (data.expiresIn - 30) * 1000; // 30초 여유
    return data; // { memberId, academyId, role, name }
  }

  async function refresh() {
    if (common) { await context(); return; }
    const res = await fetch('/api/rea/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
    });
    if (!res.ok) throw new Error('세션이 만료되었습니다. 다시 로그인하세요.');
    const data = await res.json();
    accessToken = data.accessToken;
    tokenExpiry = Date.now() + (data.expiresIn - 30) * 1000;
  }

  async function ensureToken() {
    if (common) { if (!commonContext) await context(); return; }
    if (!accessToken || Date.now() >= tokenExpiry) {
      if (!refreshPending) refreshPending = refresh().finally(() => { refreshPending = null; });
      await refreshPending; // 동시 화면 요청은 같은 갱신을 기다림
    }
  }

  async function api(path, options = {}) {
    try { await ensureToken(); } catch (error) {
      if (!common) location.href = 'AUTH-01-login.html';
      throw error;
    }
    if (common && path.startsWith('/api/rea/')) path = '/academycraft' + path;
    const res = await fetch(path, {
      ...options,
      headers: {
        ...(options.headers || {}),
        ...(!common ? {'Authorization': 'Bearer ' + accessToken} : {}),
        'Content-Type': 'application/json',
      },
      credentials: 'same-origin',
    });
    if (res.status === 401) {
      // 토큰 무효 → 로그인 페이지로
      if (common) commonLogin(); else location.href = 'AUTH-01-login.html';
      throw new Error('인증이 필요합니다.');
    }
    return res;
  }

  async function logout() {
    if (common) { location.href = "/dashboard"; return; }
    try {
      await fetch('/api/rea/auth/logout', { method: 'POST', credentials: 'same-origin' });
    } finally {
      accessToken = null;
      if (common) commonLogin(); else location.href = 'AUTH-01-login.html';
    }
  }

  async function academyPath(path) {
    await ensureToken();
    // 화면의 학원 경로만 결정한다. 권한 판단은 서버의 JWT·학원 검증이 담당한다.
    const payload = common ? commonContext : JSON.parse(atob(accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    const academyId = Number(payload.academyId);
    if (!Number.isSafeInteger(academyId) || academyId <= 0) throw new Error('학원 정보가 없습니다.');
    return path.replace(/^\/@academy(?=\/|$)/, '/' + academyId);
  }

  return { login, refresh, api, logout, academyPath, context };
})();
