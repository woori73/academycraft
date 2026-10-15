/**
 * REA 학원관리 — 공통 API 클라이언트 (5단계 작업 1)
 *
 * 모든 API 호출은 이 클라이언트의 apiGet / apiPost / apiPut / apiDelete 를 경유한다.
 * 직접 fetch() 를 호출하지 마세요.
 *
 * 동작:
 *  - localStorage 'rea_auth_key' 에서 인증키를 읽어 모든 요청에
 *    X-Auth-Key 헤더를 자동으로 추가한다.
 *  - 401 응답 → 저장된 인증키를 삭제하고 로그인 화면으로 리다이렉트한다.
 *  - 403 응답 → "권한이 없습니다" alert 을 표시한다.
 *
 * 화면별 설정 (인라인 <script> 보다 먼저, 이 파일 로드 전에 window 에 설정):
 *  - window.REA_API_BASE  : API 베이스 경로 (기본값 '/api/rea').
 *      예) 학원 ID가 고정된 화면은 `var REA_API_BASE = '/api/rea/1';`
 *      요청 시점에 평가되므로 인라인 스크립트에서 나중에 설정해도 된다.
 *  - window.REA_LOGIN_PAGE: 401 시 이동할 로그인 화면 (기본값 'MBR-07-login.html').
 *
 * 학부모용 화면(학생 인증키를 직접 입력받는 화면)은 입력받은 키를
 * 각 함수의 마지막 인자 keyOverride 로 전달한다. (localStorage 에 저장하지 않음)
 *  예) apiGet('/1/notifications', studentKey)
 *
 * ================================================================
 * 보안 주의 (반드시 읽을 것)
 * ================================================================
 * 1) 인증키는 localStorage 에 평문으로 저장된다. XSS 취약점이 있으면
 *    키가 탈취될 수 있으므로, 서버 응답을 innerHTML 에 삽입할 때는
 *    반드시 escapeHtml() 로 이스케이프할 것.
 * 2) 인증키를 URL 쿼리 파라미터(?authKey=, ?key= 등)에 절대 포함하지 말 것.
 *    (브라우저 히스토리·서버 접근 로그에 키가 남는다.) 인증키는 항상
 *    X-Auth-Key 헤더로만 전송한다.
 * 3) console.log / alert / 화면에 인증키 값을 출력하지 말 것.
 * 4) 인증키 로그인(POST /api/rea/auth/key-login)은 공개 API이므로
 *    이 클라이언트가 아닌 순수 fetch() 로 호출해야 한다.
 *    (이 클라이언트는 항상 X-Auth-Key 헤더를 붙이는데, 빈 헤더를 보내면
 *    key-login 자체가 401 로 거부된다.)
 * ================================================================
 */
(function () {
  'use strict';

  var LS_AUTH_KEY = 'rea_auth_key';

  function apiBase() {
    return (typeof window.REA_API_BASE === 'string' && window.REA_API_BASE)
      ? window.REA_API_BASE
      : '/api/rea';
  }

  function loginPage() {
    return (typeof window.REA_LOGIN_PAGE === 'string' && window.REA_LOGIN_PAGE)
      ? window.REA_LOGIN_PAGE
      : 'MBR-07-login.html';
  }

  function storedKey() {
    try {
      return localStorage.getItem(LS_AUTH_KEY);
    } catch (e) {
      return null; // private mode 등 저장소 접근 불가
    }
  }

  async function request(method, path, body, keyOverride, contentType) {
    var key = keyOverride || storedKey() || '';
    var headers = { 'X-Auth-Key': key };
    var opts = { method: method, headers: headers };
    if (body !== undefined && body !== null) {
      var ct = contentType || 'application/json';
      headers['Content-Type'] = ct;
      opts.body = (ct === 'application/json') ? JSON.stringify(body) : body;
    }

    var res = await fetch(apiBase() + path, opts);

    if (res.status === 401) {
      // 저장된 키가 무효하면 삭제 (호출자가 직접 전달한 키는 건드리지 않음)
      if (!keyOverride) {
        try { localStorage.removeItem(LS_AUTH_KEY); } catch (e) { /* 무시 */ }
      }
      var target = loginPage();
      // 리다이렉트 루프 방지: 이미 로그인 화면이면 이동하지 않음
      if (target && location.pathname.indexOf(target) === -1) {
        location.href = target;
      }
      throw new Error('인증이 필요합니다. (401) 로그인 화면으로 이동합니다.');
    }

    if (res.status === 403) {
      alert('권한이 없습니다');
      throw new Error('권한이 없습니다. (403)');
    }

    return res;
  }

  /** GET. keyOverride: 학부모 화면 등에서 입력받은 학생 인증키 (선택) */
  window.apiGet = function (path, keyOverride) {
    return request('GET', path, null, keyOverride);
  };

  /** POST (JSON body). */
  window.apiPost = function (path, body, keyOverride) {
    return request('POST', path, body, keyOverride);
  };

  /** PUT (JSON body). */
  window.apiPut = function (path, body, keyOverride) {
    return request('PUT', path, body, keyOverride);
  };

  /** DELETE. */
  window.apiDelete = function (path, keyOverride) {
    return request('DELETE', path, null, keyOverride);
  };

  /** POST (JSON이 아닌 본문: CSV 등). contentType 예) 'text/csv; charset=UTF-8' */
  window.apiPostText = function (path, text, contentType, keyOverride) {
    return request('POST', path, text, keyOverride, contentType || 'text/plain');
  };
})();
