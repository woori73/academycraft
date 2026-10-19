package com.rea.academy.integration;

import com.rea.academy.security.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
public class BuildItIntegrationController {
  private final com.rea.academy.domain.member.ReaMemberRepository members;
  public BuildItIntegrationController(com.rea.academy.domain.member.ReaMemberRepository members) { this.members=members; }
  @GetMapping("/api/rea/integration/teachers")
  public ResponseEntity<?> teachers() {
    AuthContext auth=AuthContextHolder.getOrNull();
    if(auth==null || auth.isParent()) return ResponseEntity.status(403).build();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(members.findByAcademyIdAndActiveTrue(auth.academyId()).stream()
      .filter(m->m.getRole()==com.rea.academy.domain.member.ReaRole.TEACHER || m.getRole()==com.rea.academy.domain.member.ReaRole.ACADEMY_OWNER)
      .map(m->Map.of("id",m.getId(),"name",m.getName(),"role",m.getRole().name())).toList());
  }

  @GetMapping("/api/rea/integration/context")
  public ResponseEntity<Map<String,Object>> context(jakarta.servlet.http.HttpServletRequest request) {
    AuthContext auth=AuthContextHolder.getOrNull();
    if(auth==null) return ResponseEntity.status(401).build();
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("academyOptions",request.getAttribute("buildit.academyOptions"));
    result.put("builditRole",request.getAttribute("buildit.role"));
    result.put("academyId",auth.academyId()); result.put("role",auth.role().name());
    result.put("memberId",auth.isParent()?null:auth.principalId());
    result.put("studentId",auth.isParent()?auth.ownStudentId():null);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }
}
