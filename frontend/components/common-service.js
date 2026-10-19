/* Shared BuildIt session: navigation and approved academy selection only. */
(async function () {
  if (!window.ACADEMYCRAFT_COMMON_AUTH) return;
  const supported = new Set(['OPS-02-dashboard.html','MBR-01-student-list.html','CLS-01-class-list.html','CLS-02-class-form.html','CLS-03-class-detail.html','ATT-01-attendance-keypad.html','ATT-02-attendance-monthly.html','ATT-03-attendance-roster.html','ATT-04-attendance-settings.html','CNS-01-consultation-list.html','CNS-03-inquiry-inbox.html','CNS-04-news-send.html','CNS-05-consultation-detail.html','NTC-01-notice-list.html','NTC-02-class-news.html','NTC-03-notice-form.html','NTC-04-notice-detail.html']);
  document.querySelectorAll('a[href]').forEach(link => {
    let file = link.getAttribute('href').split('?')[0];
    if(file==='CNS-01-consultation-list.html') {link.href='CNS-03-inquiry-inbox.html'; file='CNS-03-inquiry-inbox.html';}
    if (/^[A-Z]+-.*\.html$/.test(file) && !supported.has(file)) link.remove();
  });
  const nav = document.createElement('nav');
  nav.setAttribute('aria-label', 'AcademyCraft');
  nav.style.cssText = 'display:flex;gap:12px;flex-wrap:wrap;align-items:center;padding:12px;background:white;margin-bottom:12px';
  [['통합 대시보드','/dashboard'],['학원 현황','OPS-02-dashboard.html'],['학생','MBR-01-student-list.html'],['반','CLS-01-class-list.html'],['출결','ATT-03-attendance-roster.html'],['상담','CNS-03-inquiry-inbox.html'],['공지','NTC-01-notice-list.html']].forEach(([label,href])=>{
    const a=document.createElement('a'); a.textContent=label;a.href=href;a.style.cssText='display:inline-flex;align-items:center;min-height:44px';nav.appendChild(a);
  });
  document.body.prepend(nav);
  try {
    const c=await Auth.context();
    if(c.builditRole==='ADMIN') {
      const label=document.createElement('label');label.textContent='관리 학원 ';
      const select=document.createElement('select');select.setAttribute('aria-label','관리 학원');select.style.cssText='min-height:44px;max-width:100%;font-size:16px';
      (c.academyOptions||[]).forEach(a=>{const option=document.createElement('option');option.value=a.id;option.textContent=a.name;option.selected=a.id===c.academyId;select.appendChild(option);});
      select.onchange=()=>{sessionStorage.setItem('academycraft.academy',select.value);location.href='/academycraft';};label.appendChild(select);nav.appendChild(label);
    }
    ['memberid','f-author','viewer-key'].forEach(id=>{const el=document.getElementById(id);if(el){el.value=c.memberId;el.readOnly=true;}});
    const staff=document.querySelector('input[name=role][value=staff]');
    if(staff) {staff.checked=true;document.querySelectorAll('input[name=role]').forEach(el=>el.disabled=true);}
    const file=location.pathname.split('/').pop();
    if(file==='CNS-03-inquiry-inbox.html') loadList();
    if(file==='CNS-04-news-send.html') loadStudents();
    if(file==='CNS-05-consultation-detail.html' && new URLSearchParams(location.search).get('id')) loadThread();
    if(file==='NTC-04-notice-detail.html') {
      const button=document.createElement('button');button.textContent='공지 수정';button.className='btn';
      button.onclick=async()=>{
        try {
          const id=new URLSearchParams(location.search).get('id');
          const response=await apiGet('/@academy/notices/'+encodeURIComponent(id));if(!response.ok) throw new Error('공지 조회 실패');
          const n=await response.json();
          const title=prompt('제목',n.title);if(title===null)return;
          const content=prompt('내용',n.content);if(content===null)return;
          if(!title.trim()||!content.trim())throw new Error('제목과 내용을 입력하세요.');
          const saved=await apiPut('/@academy/notices/'+encodeURIComponent(id),{title,content});if(!saved.ok)throw new Error('수정 권한 또는 내용을 확인하세요.');
          await loadDetail();
        } catch(e) {alert(e.message);}
      };nav.appendChild(button);
    }
  } catch(e) {
    const message=document.createElement('p');message.setAttribute('role','alert');message.textContent=e.message;nav.appendChild(message);
    const reset=document.createElement('button');reset.textContent='학원 선택 초기화';reset.onclick=()=>{sessionStorage.removeItem('academycraft.academy');location.href='/academycraft';};nav.appendChild(reset);
  }
})();
