const assert = require('node:assert/strict');
const test = require('node:test');
const R = require('../components/screen-rules.js');

// 출결부·학생 화면 규칙. 값은 테스트용 가상 데이터다.

test('given UTC 새벽(한국 오전) when 오늘을 구하면 then 한국 날짜를 쓴다', () => {
  // given: 2026-11-10 23:30 UTC = 2026-11-11 08:30 KST
  const now = new Date('2026-11-10T23:30:00Z');
  // when / then
  assert.equal(R.kstToday(now), '2026-11-11');
});

test('given 주소 조건 when 출결부를 열면 then 반·날짜를 쓰고 잘못된 값은 버린다', () => {
  assert.deepEqual(R.rosterQuery('?classId=3&date=2026-11-10', '2026-11-11'), { classId: '3', date: '2026-11-10' });
  assert.deepEqual(R.rosterQuery('?classId=abc&date=11-10', '2026-11-11'), { classId: '', date: '2026-11-11' });
  assert.deepEqual(R.rosterQuery('', '2026-11-11'), { classId: '', date: '2026-11-11' });
});

test('given 오늘 출결부 when 행에서 출석을 저장하면 then 시각을 보내지 않아 서버 현재 시각으로 기록된다', () => {
  // when
  const body = R.checkBody('7', 'CHECK_IN', '  ', '2026-11-11', '2026-11-11', '16:00');
  // then
  assert.deepEqual(body, { studentId: 7, eventType: 'CHECK_IN', note: null });
});

test('given 지난 날짜 출결부 when 결석을 저장하면 then 그 날짜의 수업 시작 시각으로 기록한다', () => {
  assert.equal(R.checkBody(7, 'ABSENT', '병결', '2026-11-09', '2026-11-11', '16:00:00').occurredAt, '2026-11-09T16:00');
  assert.equal(R.checkBody(7, 'LATE', '', '2026-11-09', '2026-11-11', null).occurredAt, '2026-11-09T12:00');
});

test('given 알 수 없는 구분 when 저장하면 then 막는다', () => {
  assert.throws(() => R.checkBody(7, 'PRESENT', '', '2026-11-11', '2026-11-11'), /출결 구분/);
});

test('given 기존 기록 정정 when 사유가 없으면 then 막고, 있으면 구분·사유를 보낸다', () => {
  assert.throws(() => R.correctionBody('ABSENT', '  '), /정정 사유/);
  assert.deepEqual(R.correctionBody('LATE', ' 버스 지연 '), { eventType: 'LATE', reason: '버스 지연' });
  assert.throws(() => R.correctionBody('', '사유'), /출결 구분/);
});

test('given 출결부 행 when 요약하면 then 기록·미기록을 구분해 센다', () => {
  const s = R.rosterSummary([{ status: 'NONE' }, { status: 'PRESENT' }, { status: 'LATE' }, { status: 'NONE' }]);
  assert.equal(s.total, 4);
  assert.equal(s.recorded, 2);
  assert.equal(s.missing, 2);
  assert.equal(s.byStatus.NONE, 2);
});

test('given 학생 입력폼 when 저장하면 then 이름 필수·공백 정리·학교 포함 다섯 항목을 보낸다', () => {
  assert.throws(() => R.studentBody({ name: '  ' }), /이름/);
  assert.deepEqual(R.studentBody({ name: ' 가상학생 ', grade: '중2', schoolName: ' 새봄중 ', parentName: '', parentPhone: null }),
    { name: '가상학생', grade: '중2', schoolName: '새봄중', parentName: '', parentPhone: '' });
  assert.throws(() => R.studentBody({ name: 'x'.repeat(51) }), /50자/);
});

test('given 검색·반 필터 when 명단을 거르면 then 전체 인원은 필터와 무관하게 활성 학생 고유 인원이다', () => {
  // given
  const students = [{ id: 1, name: '가상A', schoolName: '새봄중' }, { id: 2, name: '가상B', grade: '고1' }, { id: 3, name: '다른C' }];
  // when
  const r = R.filterStudents(students, '가상', new Set([2, 3]));
  // then
  assert.equal(r.total, 3);
  assert.deepEqual(r.shown.map((s) => s.id), [2]);
  assert.equal(R.filterStudents(students, '새봄').shown[0].id, 1);
});

test('given 여러 반 수강생 when 학생별 소속 반을 모으면 then 반 이름을 모두 보이고 사람은 한 번만 있다', () => {
  const map = R.classesByStudent([{ id: 10, name: 'A반', students: [{ id: 1 }, { id: 2 }] }, { id: 11, name: 'B반', students: [{ id: 1 }] }]);
  assert.deepEqual(map[1].map((c) => c.name), ['A반', 'B반']);
  assert.equal(Object.keys(map).length, 2);
});
