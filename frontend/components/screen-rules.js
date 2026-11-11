/**
 * AcademyCraft 업무 화면 공통 규칙(순수 함수). 화면(DOM)·서버 호출과 분리해 node 테스트로 검증한다.
 * 브라우저: window.ScreenRules, 테스트: require().
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ScreenRules = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /** 한국 시간 기준 오늘(YYYY-MM-DD). toISOString()은 UTC라 오전 9시 전에 어제가 된다. */
  function kstToday(now) {
    var d = new Date((now || new Date()).getTime() + 9 * 3600 * 1000);
    return d.toISOString().slice(0, 10);
  }

  function isDate(v) { return /^\d{4}-\d{2}-\d{2}$/.test(String(v || '')); }
  function isId(v) { return /^[1-9]\d{0,17}$/.test(String(v || '')); }

  /** 출결부 주소 조건. 잘못된 값은 버리고 날짜는 오늘로. */
  function rosterQuery(search, today) {
    var q = new URLSearchParams(search || '');
    return { classId: isId(q.get('classId')) ? q.get('classId') : '', date: isDate(q.get('date')) ? q.get('date') : today };
  }

  /** 출결부 상태 표시(서버 roster status). */
  var STATUS_LABEL = { PRESENT: '출석', CHECKED_OUT: '하원', ABSENT: '결석', LATE: '지각', EARLY_LEAVE: '조퇴', NONE: '미기록' };

  /** 행에서 고를 수 있는 기록(서버 eventType). 앞의 셋이 주 동작, 조퇴·하원은 기존 지원 유형 보존. */
  var EVENT_CHOICES = [['CHECK_IN', '출석'], ['LATE', '지각'], ['ABSENT', '결석'], ['EARLY_LEAVE', '조퇴'], ['CHECK_OUT', '하원']];
  var EVENT_LABEL = { CHECK_IN: '출석(등원)', CHECK_OUT: '하원', ABSENT: '결석', LATE: '지각', EARLY_LEAVE: '조퇴' };

  /**
   * 출결 기록 요청 본문. 오늘이 아닌 날짜를 보고 있으면 그 날짜(수업 시작 시각, 없으면 정오)로 기록한다.
   * (예전에는 다른 날짜 출결부에서 눌러도 '지금'으로 저장돼 오늘 기록이 됐다.)
   */
  function checkBody(studentId, eventType, note, date, today, startTime) {
    if (!EVENT_LABEL[eventType]) throw new Error('출결 구분을 선택하세요.');
    var body = { studentId: Number(studentId), eventType: eventType, note: String(note || '').trim() || null };
    if (date && date !== today) {
      var t = /^(\d{2}):(\d{2})/.exec(String(startTime || '')) ? String(startTime).slice(0, 5) : '12:00';
      body.occurredAt = date + 'T' + t;
    }
    return body;
  }

  /** 기존 기록 정정 본문. 사유는 필수(서버도 400으로 막는다). */
  function correctionBody(eventType, reason, note) {
    if (!EVENT_LABEL[eventType]) throw new Error('정정할 출결 구분을 선택하세요.');
    var r = String(reason || '').trim();
    if (!r) throw new Error('정정 사유를 입력하세요.');
    var body = { eventType: eventType, reason: r };
    if (note != null && String(note).trim()) body.note = String(note).trim();
    return body;
  }

  /** 출결부 요약: 전체·기록·미기록과 상태별 수. */
  function rosterSummary(entries) {
    var out = { total: 0, recorded: 0, missing: 0, byStatus: {} };
    (entries || []).forEach(function (e) {
      out.total++;
      if (e.status === 'NONE') out.missing++; else out.recorded++;
      out.byStatus[e.status] = (out.byStatus[e.status] || 0) + 1;
    });
    return out;
  }

  /** 학생 폼 값 → 서버 본문. 이름 필수, 앞뒤 공백 제거, 빈 값은 빈 문자열(서버: null=유지, ''=비우기 아님 → 이름 외는 그대로 저장). */
  function studentBody(form) {
    var name = String(form.name || '').trim();
    if (!name) throw new Error('학생 이름을 입력하세요.');
    if (name.length > 50) throw new Error('학생 이름은 50자 이하로 입력하세요.');
    var body = { name: name };
    ['grade', 'schoolName', 'parentName', 'parentPhone'].forEach(function (k) { body[k] = String(form[k] == null ? '' : form[k]).trim(); });
    return body;
  }

  /**
   * 학생 명단 필터. 전체 인원(total)은 검색·반 필터와 분리한다(재원생 수 = 활성 학생 전체).
   * students: 활성 학생 배열, keyword: 이름·학교·학년 포함 검색, classStudentIds: 반 필터(Set|null)
   */
  function filterStudents(students, keyword, classStudentIds) {
    var k = String(keyword || '').trim().toLowerCase();
    var list = (students || []).filter(function (s) {
      if (classStudentIds && !classStudentIds.has(s.id)) return false;
      if (!k) return true;
      return [s.name, s.schoolName, s.grade].some(function (v) { return String(v || '').toLowerCase().indexOf(k) >= 0; });
    });
    var seen = new Set();
    (students || []).forEach(function (s) { seen.add(s.id); });
    return { total: seen.size, shown: list };
  }

  /** 반 상세 목록 → 학생별 소속 반 이름. 여러 반 수강생도 한 사람으로 센다. */
  function classesByStudent(classDetails) {
    var map = {};
    (classDetails || []).forEach(function (c) {
      (c.students || []).forEach(function (s) { (map[s.id] = map[s.id] || []).push({ id: c.id, name: c.name }); });
    });
    return map;
  }

  return {
    kstToday: kstToday, rosterQuery: rosterQuery, STATUS_LABEL: STATUS_LABEL, EVENT_CHOICES: EVENT_CHOICES, EVENT_LABEL: EVENT_LABEL,
    checkBody: checkBody, correctionBody: correctionBody, rosterSummary: rosterSummary, studentBody: studentBody,
    filterStudents: filterStudents, classesByStudent: classesByStudent, isId: isId, isDate: isDate
  };
});
