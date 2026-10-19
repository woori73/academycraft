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
}
