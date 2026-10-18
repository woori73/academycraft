/* 홈 이동 시 작성 중인 입력을 잃지 않도록 보수적으로 확인한다. */
(function () {
  'use strict';
  var edited = false;
  function record(event) {
    var target = event.target;
    if (target && target.matches('input:not([type="hidden"]):not([type="password"]), textarea, select, [contenteditable="true"]')) edited = true;
  }
  document.addEventListener('input', record, true);
  document.addEventListener('change', record, true);
  document.addEventListener('click', function (event) {
    var link = event.target.closest('a[data-feel2real-home]');
    if (link && edited && !window.confirm('입력한 내용이 있습니다. 저장 여부를 확인한 뒤 홈으로 이동할까요?')) event.preventDefault();
  }, true);
})();
