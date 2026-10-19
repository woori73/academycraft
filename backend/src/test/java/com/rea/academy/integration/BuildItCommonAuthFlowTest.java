package com.rea.academy.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
  "spring.datasource.url=jdbc:h2:mem:buildit_common_auth;DB_CLOSE_DELAY=-1",
  "academycraft.gateway-secret=test-only-shared-secret-with-at-least-32-bytes",
  "academycraft.buildit-bindings=[{\"userId\":54,\"role\":\"TUTOR\",\"builditAcademyId\":12,\"academyId\":7001,\"memberId\":9001},{\"userId\":4,\"role\":\"ADMIN\",\"builditAcademyId\":12,\"academyId\":7001,\"memberId\":9002},{\"userId\":81,\"role\":\"TUTOR\",\"builditAcademyId\":12,\"academyId\":7001,\"memberId\":9003}]"
})
@AutoConfigureMockMvc
@Transactional
class BuildItCommonAuthFlowTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @BeforeEach void fixtures(){
    jdbc.update("INSERT INTO rea_academies(id,name,active,created_at,updated_at) VALUES(7001,'isolated academy',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    for(int i=1;i<=3;i++) jdbc.update("INSERT INTO rea_members(id,academy_id,role,name,auth_key,active,failed_login_attempts,created_at,updated_at) VALUES(?,7001,?,?,?,true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",9000+i,i==3?"TEACHER":"ACADEMY_OWNER","isolated role "+i,"test-common-auth-"+i);
  }
  MvcResult call(long user,String role,String method,String path,String body) throws Exception {
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
    String a=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(new BuildItGatewayFilter.Identity(user,role,user==54?12L:null,Instant.now().getEpochSecond(),UUID.randomUUID().toString())));
    return mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path)
      .contentType("application/json").content(bytes).header("X-BuildIt-Assertion",a)
      .header("X-BuildIt-Signature",BuildItGatewayFilter.sign(BuildItGatewayFilterTest.SECRET,method,path,a,bytes))).andReturn();
  }
  long id(MvcResult r) throws Exception { assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString());return json.readTree(r.getResponse().getContentAsString()).get("id").asLong(); }
  @Test void existingIdentityCanUseStudentClassAndAttendanceWithoutLoginAccountCreation() throws Exception {
    long student=id(call(54,"TUTOR","POST","/api/rea/7001/students","{\"name\":\"isolated QA student\",\"grade\":\"고1\"}"));
    assertEquals(200,call(54,"TUTOR","PUT","/api/rea/7001/students/"+student,"{\"name\":\"isolated QA updated\"}").getResponse().getStatus());
    long clazz=id(call(54,"TUTOR","POST","/api/rea/7001/classes","{\"name\":\"isolated QA class\",\"teacherId\":9001}"));
    assertEquals(200,call(54,"TUTOR","PUT","/api/rea/7001/classes/"+clazz,"{\"name\":\"isolated QA class updated\"}").getResponse().getStatus());
    assertEquals(200,call(54,"TUTOR","POST","/api/rea/7001/classes/"+clazz+"/students","{\"studentIds\":["+student+"]}").getResponse().getStatus());
    MvcResult event=call(54,"TUTOR","POST","/api/rea/7001/attendance/check","{\"studentId\":"+student+",\"eventType\":\"CHECK_IN\"}");
    assertEquals(200,event.getResponse().getStatus(),event.getResponse().getContentAsString());
    var value=json.readTree(event.getResponse().getContentAsString());
    long eventId=value.has("event")?value.get("event").get("id").asLong():value.get("id").asLong();
    assertEquals(200,call(54,"TUTOR","PUT","/api/rea/7001/attendance/"+eventId,"{\"eventType\":\"LATE\",\"reason\":\"isolated QA correction\"}").getResponse().getStatus());
    assertEquals(200,call(54,"TUTOR","GET","/api/rea/7001/attendance/student/"+student,"").getResponse().getStatus());
    assertEquals(200,call(4,"ADMIN","GET","/api/rea/7001/dashboard/stats","").getResponse().getStatus());
    assertEquals(403,call(54,"TUTOR","GET","/api/rea/7002/students","").getResponse().getStatus());
    assertEquals(403,call(81,"TUTOR","POST","/api/rea/7001/classes","{\"name\":\"blocked\",\"teacherId\":9003}").getResponse().getStatus());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rea_members WHERE login_id IS NOT NULL OR password_hash IS NOT NULL",Integer.class));
  }
}
