/** AcademyCraft 업무 API 클라이언트.
 * Auth.api의 메모리 Access Token과 HttpOnly Refresh 쿠키를 재사용한다.
 * /@academy/ 경로는 JWT academyId로 결정하며 서버가 학원 권한을 검증한다.
 * keyOverride 인자는 이전 화면의 호출 호환용이며 인증키를 보내지 않는다.
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
    if(window.ACADEMYCRAFT_COMMON_AUTH) return '/?login=required&returnUrl=%2Facademycraft';
    return (typeof window.REA_LOGIN_PAGE === 'string' && window.REA_LOGIN_PAGE)
      ? window.REA_LOGIN_PAGE
      : 'AUTH-01-login.html';
  }

  async function request(method, path, body, keyOverride, contentType) {
    var headers = {};
    var opts = { method: method, headers: headers };
    if (body !== undefined && body !== null) {
      var ct = contentType || 'application/json';
      headers['Content-Type'] = ct;
      opts.body = (ct === 'application/json') ? JSON.stringify(body) : body;
    }
    // 기존 업무 API와 JWT 헬퍼를 연결한다. 운영 인증키 폴백은 사용하지 않는다.
    var resolved;
    try { resolved = await Auth.academyPath(path); }
    catch (error) { if (!window.ACADEMYCRAFT_COMMON_AUTH) location.href = 'AUTH-01-login.html'; throw error; }
    var res = await Auth.api(apiBase() + resolved, opts);

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
