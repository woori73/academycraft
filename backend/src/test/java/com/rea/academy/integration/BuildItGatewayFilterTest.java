package com.rea.academy.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rea.academy.domain.admin.*;
import com.rea.academy.domain.member.*;
import com.rea.academy.security.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildItGatewayFilterTest {
  static final String SECRET="test-only-shared-secret-with-at-least-32-bytes";
  ObjectMapper json=new ObjectMapper();
  ReaMemberRepository members=mock(ReaMemberRepository.class);
  ReaStudentRepository students=mock(ReaStudentRepository.class);
  ReaAcademyRepository academies=mock(ReaAcademyRepository.class);
  BuildItGatewayFilter filter;
  @BeforeEach void setup() throws Exception {
    var binding=List.of(new BuildItGatewayFilter.Binding(54L,"TUTOR",12L,7L,9L,null),
      new BuildItGatewayFilter.Binding(4L,"ADMIN",12L,7L,10L,null),
      new BuildItGatewayFilter.Binding(4L,"ADMIN",13L,8L,12L,null),
      new BuildItGatewayFilter.Binding(80L,"STUDENT",12L,7L,null,22L),
      new BuildItGatewayFilter.Binding(81L,"TUTOR",12L,7L,11L,null));
    filter=new BuildItGatewayFilter(json,members,students,academies,SECRET,json.writeValueAsString(binding));
    when(academies.findById(8L)).thenReturn(Optional.of(ReaAcademy.builder().id(8L).name("second").active(true).build()));
    when(members.findById(12L)).thenReturn(Optional.of(ReaMember.builder().id(12L).academyId(8L).role(ReaRole.ACADEMY_OWNER).active(true).name("second admin scope").build()));
    when(academies.findById(7L)).thenReturn(Optional.of(ReaAcademy.builder().id(7L).active(true).build()));
    when(members.findById(9L)).thenReturn(Optional.of(ReaMember.builder().id(9L).academyId(7L).role(ReaRole.ACADEMY_OWNER).active(true).name("owner").build()));
    when(members.findById(10L)).thenReturn(Optional.of(ReaMember.builder().id(10L).academyId(7L).role(ReaRole.TEACHER).active(true).name("admin with scoped teacher role").build()));
    when(members.findById(11L)).thenReturn(Optional.of(ReaMember.builder().id(11L).academyId(7L).role(ReaRole.TEACHER).active(true).name("teacher").build()));
    when(students.findById(22L)).thenReturn(Optional.of(ReaStudent.builder().id(22L).academyId(7L).active(true).name("student").build()));
  }
  MockHttpServletRequest request(Long user,String role,Long owner,String method,String path, long time) throws Exception {
    var req=new MockHttpServletRequest(method,path);req.setContent(new byte[0]);
    String assertion=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(new BuildItGatewayFilter.Identity(user,role,owner,time,UUID.randomUUID().toString())));
    req.addHeader("X-BuildIt-Assertion",assertion);req.addHeader("X-BuildIt-Signature",BuildItGatewayFilter.sign(SECRET,method,path,assertion,new byte[0]));return req;
  }
  /** 프로토콜(agentProtocol)·본문을 지정하는 서명 요청. 본문 해시를 서명에 포함한다. */
  MockHttpServletRequest agentRequest(Long user,String role,Long owner,String method,String path,Integer protocol,byte[] body) throws Exception {
    var req=new MockHttpServletRequest(method,path);req.setContent(body);
    String assertion=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(new BuildItGatewayFilter.Identity(user,role,owner,Instant.now().getEpochSecond(),UUID.randomUUID().toString(),protocol)));
    req.addHeader("X-BuildIt-Assertion",assertion);req.addHeader("X-BuildIt-Signature",BuildItGatewayFilter.sign(SECRET,method,path,assertion,body));return req;
  }
  static final String PLAN="11111111-1111-1111-1111-111111111111";
  void expect(Long user,String role,Long owner,String method,String path,int status) throws Exception {
    var response=new MockHttpServletResponse();
    filter.doFilter(request(user,role,owner,method,path,Instant.now().getEpochSecond()),response,(req,res)->{
      if(path.startsWith("/api/rea/7/") && !role.equals("STUDENT")) ReaAccessRules.check((jakarta.servlet.http.HttpServletRequest)req,AuthContextHolder.getOrNull());
      ((jakarta.servlet.http.HttpServletResponse)res).setStatus(204);
    });assertEquals(status,response.getStatus()); assertNull(AuthContextHolder.getOrNull());
  }
  @Test void moduUsesExistingOwnerOnlyInOwnAcademy() throws Exception { expect(54L,"TUTOR",12L,"GET","/api/rea/7/students",204); expect(54L,"TUTOR",11L,"GET","/api/rea/7/students",403); }
  @Test void adminSelectsOnlyApprovedAcademies() throws Exception { expect(4L,"ADMIN",null,"GET","/api/rea/8/notices",204); expect(4L,"ADMIN",null,"GET","/api/rea/9/notices",403); expect(54L,"TUTOR",12L,"GET","/api/rea/8/notices",403); }
  @Test void staffCanUseConsultationsAndNotices() throws Exception { expect(54L,"TUTOR",12L,"POST","/api/rea/7/notices",204);expect(54L,"TUTOR",12L,"POST","/api/rea/7/consultations",204); }
  @Test void adminDoesNotBecomeUnrestrictedOwner() throws Exception { expect(4L,"ADMIN",20L,"GET","/api/rea/7/classes",204); }
  @Test void tutorCannotReadOtherAcademyOrManageAccounts() throws Exception { expect(81L,"TUTOR",null,"GET","/api/rea/8/students",403); expect(81L,"TUTOR",null,"POST","/api/rea/7/members",403); }
  @Test void studentCanOnlyReadOwnStudentAndAttendance() throws Exception { expect(80L,"STUDENT",null,"GET","/api/rea/7/attendance/student/22",204); expect(80L,"STUDENT",null,"GET","/api/rea/7/attendance/student/23",403); expect(80L,"STUDENT",null,"POST","/api/rea/7/attendance/check",403); expect(80L,"STUDENT",null,"GET","/api/rea/7/dashboard/stats",403); }
  @Test void unboundIdentityAndRoleChangeFailClosed() throws Exception { expect(99L,"ADMIN",null,"GET","/api/rea/7/students",403); expect(54L,"ADMIN",12L,"GET","/api/rea/7/students",403); }
  @Test void replayExpiryAndBodyTamperingRejected() throws Exception {
    var req=request(54L,"TUTOR",12L,"GET","/api/rea/7/students",Instant.now().getEpochSecond());
    var first=new MockHttpServletResponse();filter.doFilter(req,first,(r,s)->{});assertEquals(200,first.getStatus());
    var second=new MockHttpServletResponse();filter.doFilter(req,second,(r,s)->fail());assertEquals(401,second.getStatus());
    var expired=new MockHttpServletResponse();filter.doFilter(request(54L,"TUTOR",12L,"GET","/api/rea/7/students",Instant.now().getEpochSecond()-90),expired,(r,s)->fail());assertEquals(401,expired.getStatus());
    var tampered=request(54L,"TUTOR",12L,"POST","/api/rea/7/students",Instant.now().getEpochSecond());tampered.setContent("changed".getBytes(StandardCharsets.UTF_8));
    var response=new MockHttpServletResponse();filter.doFilter(tampered,response,(r,s)->fail());assertEquals(401,response.getStatus());
  }
  @Test void bodyPreservedAndContextClearedOnHandlerFailure() throws Exception {
    var req=request(54L,"TUTOR",12L,"POST","/api/rea/7/students",Instant.now().getEpochSecond());byte[] body="{\"name\":\"QA\"}".getBytes(StandardCharsets.UTF_8);req.setContent(body);
    req.removeHeader("X-BuildIt-Signature"); req.addHeader("X-BuildIt-Signature",BuildItGatewayFilter.sign(SECRET,"POST",req.getRequestURI(),req.getHeader("X-BuildIt-Assertion"),body));
    assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(req,new MockHttpServletResponse(),(r,s)->{assertArrayEquals(body,r.getInputStream().readAllBytes());throw new jakarta.servlet.ServletException("test");}));assertNull(AuthContextHolder.getOrNull());
  }

  // ===== P1-b: 에이전트 정확 경로 =====

  // given 원장 권한 회원에 연결된 TUTOR / when 프로토콜 1로 에이전트 경로를 호출하면 / then 통과하고 서명 검증된 연결 속성이 노출된다
  @Test void agentPathPassesWithProtocolAndExposesSignedConnectionAttributes() throws Exception {
    var req=agentRequest(54L,"TUTOR",12L,"POST","/api/rea/7/agent/execute",1,"{\"command\":\"오늘 결석 학생\",\"idempotencyKey\":\"x\"}".getBytes(StandardCharsets.UTF_8));
    var response=new MockHttpServletResponse();var seen=new Object[2];
    filter.doFilter(req,response,(r,s)->{seen[0]=r.getAttribute("buildit.userId");seen[1]=r.getAttribute("buildit.academyId");((jakarta.servlet.http.HttpServletResponse)s).setStatus(204);});
    assertEquals(204,response.getStatus()); assertEquals(54L,seen[0]); assertEquals(12L,seen[1]);
  }

  // given 이전 버전 BuildIt(프로토콜 없음) / when 에이전트 경로를 호출하면 / then 403 AGENT_PROTOCOL_MISMATCH, 같은 요청자의 기존 경로는 영향 없음
  @Test void agentPathFailsSafelyOnProtocolMismatchWhileLegacyPathsStillWork() throws Exception {
    for (Integer protocol : new Integer[]{null,0,2}) {
      var response=new MockHttpServletResponse();
      filter.doFilter(agentRequest(54L,"TUTOR",12L,"GET","/api/rea/7/agent/tools",protocol,new byte[0]),response,(r,s)->fail("에이전트 경로가 통과하면 안 된다"));
      assertEquals(403,response.getStatus()); assertTrue(response.getContentAsString().contains("AGENT_PROTOCOL_MISMATCH"));
    }
    var legacy=new MockHttpServletResponse();
    filter.doFilter(agentRequest(54L,"TUTOR",12L,"GET","/api/rea/7/students",null,new byte[0]),legacy,(r,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(204));
    assertEquals(204,legacy.getStatus());
  }

  // given 에이전트 정확 경로 밖의 요청 / when 호출하면 / then 프로토콜이 맞아도 403
  @Test void nonExactAgentPathsDeniedEvenWithProtocol() throws Exception {
    for (String[] c : new String[][]{{"GET","/api/rea/7/agent"},{"POST","/api/rea/7/agent/tools"},{"GET","/api/rea/7/agent/plans/"+PLAN+"/approve"},{"DELETE","/api/rea/7/agent/plans/"+PLAN},{"POST","/api/rea/7/agent/plans/not-a-uuid/approve"}}) {
      var response=new MockHttpServletResponse();
      filter.doFilter(agentRequest(54L,"TUTOR",12L,c[0],c[1],1,new byte[0]),response,(r,s)->fail("통과하면 안 된다: "+c[1]));
      assertEquals(403,response.getStatus(),c[0]+" "+c[1]);
    }
  }

  // given 연결되지 않은 학원 / 학생 연결 / when 에이전트 경로를 호출하면 / then 403 (타 학원·학생 차단)
  @Test void agentPathBlockedForOtherAcademyStudentAndUnbound() throws Exception {
    for (Object[] c : new Object[][]{{81L,"TUTOR",null,"/api/rea/8/agent/tools"},{80L,"STUDENT",null,"/api/rea/7/agent/tools"},{99L,"ADMIN",null,"/api/rea/7/agent/tools"},{54L,"ADMIN",12L,"/api/rea/7/agent/tools"}}) {
      var response=new MockHttpServletResponse();
      filter.doFilter(agentRequest((Long)c[0],(String)c[1],(Long)c[2],"GET",(String)c[3],1,new byte[0]),response,(r,s)->fail("통과하면 안 된다: "+c[3]));
      assertEquals(403,response.getStatus(),String.valueOf(c[3]));
    }
  }

  // given 서명된 요청 / when 본문(idempotencyKey 포함)이 바뀌거나 재전송되면 / then 401
  @Test void agentRequestBodyIsSignedAndReplayRejected() throws Exception {
    var body="{\"tool\":\"registerStudent\",\"idempotencyKey\":\"a\"}".getBytes(StandardCharsets.UTF_8);
    var tampered=agentRequest(54L,"TUTOR",12L,"POST","/api/rea/7/agent/execute",1,body);
    tampered.setContent("{\"tool\":\"registerStudent\",\"idempotencyKey\":\"b\"}".getBytes(StandardCharsets.UTF_8));
    var r1=new MockHttpServletResponse();filter.doFilter(tampered,r1,(r,s)->fail());assertEquals(401,r1.getStatus());
    var original=agentRequest(54L,"TUTOR",12L,"POST","/api/rea/7/agent/execute",1,body);
    var ok=new MockHttpServletResponse();filter.doFilter(original,ok,(r,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(204));assertEquals(204,ok.getStatus());
    var replay=new MockHttpServletResponse();filter.doFilter(original,replay,(r,s)->fail());assertEquals(401,replay.getStatus());
  }

  // given 강사 회원에 연결된 TUTOR / when 승인·취소를 호출하면 / then 경로는 통과하지만 기존 역할 규칙이 403, 조회·읽기 실행은 통과
  @Test void teacherBoundTutorCannotApproveOrCancelButCanReadAndExecute() throws Exception {
    for (String path : new String[]{"/api/rea/7/agent/plans/"+PLAN+"/approve","/api/rea/7/agent/plans/"+PLAN+"/cancel"}) {
      assertThrows(ReaForbiddenException.class,()->filter.doFilter(agentRequest(81L,"TUTOR",null,"POST",path,1,new byte[0]),new MockHttpServletResponse(),
          (r,s)->ReaAccessRules.check((jakarta.servlet.http.HttpServletRequest)r,AuthContextHolder.getOrNull())),path);
    }
    for (String[] c : new String[][]{{"GET","/api/rea/7/agent/tools"},{"GET","/api/rea/7/agent/plans/"+PLAN},{"POST","/api/rea/7/agent/execute"}}) {
      var response=new MockHttpServletResponse();
      filter.doFilter(agentRequest(81L,"TUTOR",null,c[0],c[1],1,new byte[0]),response,(r,s)->{
        ReaAccessRules.check((jakarta.servlet.http.HttpServletRequest)r,AuthContextHolder.getOrNull());((jakarta.servlet.http.HttpServletResponse)s).setStatus(204);});
      assertEquals(204,response.getStatus(),c[1]);
    }
  }

  // given 원장 권한 회원에 연결된 TUTOR / when 승인·취소를 호출하면 / then 기존 역할 규칙을 통과한다 (OWNER·TEACHER 분리 유지)
  @Test void ownerBoundTutorPassesApproveAndCancelRules() throws Exception {
    for (String suffix : new String[]{"/approve","/cancel"}) {
      var response=new MockHttpServletResponse();
      filter.doFilter(agentRequest(54L,"TUTOR",12L,"POST","/api/rea/7/agent/plans/"+PLAN+suffix,1,new byte[0]),response,(r,s)->{
        ReaAccessRules.check((jakarta.servlet.http.HttpServletRequest)r,AuthContextHolder.getOrNull());((jakarta.servlet.http.HttpServletResponse)s).setStatus(204);});
      assertEquals(204,response.getStatus(),suffix);
    }
  }
}
