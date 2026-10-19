package com.rea.academy.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rea.academy.domain.admin.ReaAcademyRepository;
import com.rea.academy.domain.member.*;
import com.rea.academy.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Signed server-to-server gateway only. Existing JWT login and tenant rules remain unchanged. */
public class BuildItGatewayFilter extends OncePerRequestFilter {
  private final ObjectMapper json;
  private final ReaMemberRepository members;
  private final ReaStudentRepository students;
  private final ReaAcademyRepository academies;
  private final String secret;
  private final List<Binding> bindings;
  private final Map<String, Long> nonces = new ConcurrentHashMap<>();

  public BuildItGatewayFilter(ObjectMapper json, ReaMemberRepository members, ReaStudentRepository students,
      ReaAcademyRepository academies, String secret, String bindingsJson) throws Exception {
    this.json=json; this.members=members; this.students=students; this.academies=academies; this.secret=secret;
    this.bindings=List.of(json.readValue(bindingsJson, Binding[].class));
    Set<String> users=new HashSet<>(); Set<Long> localMembers=new HashSet<>(); Set<Long> localStudents=new HashSet<>();
    for (Binding b:bindings) {
      if (b.userId()==null || b.userId()<=0 || b.builditAcademyId()==null || b.builditAcademyId()<=0
          || b.academyId()==null || b.academyId()<=0 || !users.add(b.userId()+":"+("ADMIN".equals(b.role())?b.academyId():"single"))
          || !Set.of("ADMIN","TUTOR","STUDENT").contains(b.role())) throw new IllegalArgumentException("Invalid or ambiguous AcademyCraft binding");
      if (b.role().equals("STUDENT")) {
        if (b.studentId()==null || !localStudents.add(b.studentId()) || b.memberId()!=null) throw new IllegalArgumentException("Invalid student binding");
      } else if (b.memberId()==null || !localMembers.add(b.memberId()) || b.studentId()!=null) throw new IllegalArgumentException("Invalid member binding");
    }
  }

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getHeader("X-BuildIt-Assertion")==null && request.getHeader("X-BuildIt-Signature")==null;
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean authenticated=false;
    try {
      if (secret.getBytes(StandardCharsets.UTF_8).length<32) { reject(response,503); return; }
      String assertion=request.getHeader("X-BuildIt-Assertion"), signature=request.getHeader("X-BuildIt-Signature");
      if (assertion==null || signature==null || assertion.length()>2048 || signature.length()!=64) { reject(response,401); return; }
      byte[] body=request.getInputStream().readNBytes(1_048_577);
      if (body.length>1_048_576) { reject(response,413); return; }
      String target=request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString());
      if (!MessageDigest.isEqual(HexFormat.of().parseHex(signature), HexFormat.of().parseHex(sign(secret,request.getMethod(),target,assertion,body)))) { reject(response,401); return; }
      Identity identity=json.readValue(Base64.getUrlDecoder().decode(assertion),Identity.class);
      long now=Instant.now().getEpochSecond();
      if ((identity.issuedAt()<now-30 || identity.issuedAt()>now+5) || identity.nonce()==null || !identity.nonce().matches("[a-f0-9-]{36}")) { reject(response,401); return; }
      nonces.entrySet().removeIf(e->e.getValue()<now-31);
      if (nonces.size()>10000 || nonces.putIfAbsent(identity.nonce(),identity.issuedAt())!=null) { reject(response,401); return; }
      List<Binding> permitted=bindings.stream().filter(b->b.userId().equals(identity.userId()) && b.role().equals(identity.role()))
          .filter(b->resolve(identity,b)!=null && academies.findById(b.academyId()).map(a->Boolean.TRUE.equals(a.getActive())).orElse(false)).toList();
      Long selected=null;
      String[] segments=request.getRequestURI().split("/");
      if (segments.length>3 && segments[3].matches("[1-9][0-9]*")) selected=Long.valueOf(segments[3]);
      else if (request.getRequestURI().startsWith("/api/rea/integration/") && request.getParameter("academyId")!=null) selected=Long.valueOf(request.getParameter("academyId"));
      final Long selection=selected;
      Binding binding=permitted.stream().filter(b->selection==null || b.academyId().equals(selection)).findFirst().orElse(null);
      request.setAttribute("buildit.academyOptions",permitted.stream().map(b->{
        var a=academies.findById(b.academyId()).orElseThrow();
        return Map.of("id",b.academyId(),"name",a.getName()==null?"학원 "+b.academyId():a.getName());
      }).toList());
      request.setAttribute("buildit.role",identity.role());
      if (binding==null || !academies.findById(binding.academyId()).map(a->Boolean.TRUE.equals(a.getActive())).orElse(false)) { reject(response,403); return; }
      AuthContext context=resolve(identity,binding);
      if (context==null) { reject(response,403); return; }
      String path=request.getRequestURI();
      if (!allowed(path,request.getMethod(),binding)) { reject(response,403); return; }
      authenticated=true;
      AuthContextHolder.set(context);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(context,null,
          List.of(new SimpleGrantedAuthority("ROLE_"+context.role().name()))));
      try { chain.doFilter(new CachedRequest(request,body),response); }
      finally { AuthContextHolder.clear(); SecurityContextHolder.clearContext(); }
    } catch (GeneralSecurityException e) { reject(response,401); }
      catch (IllegalArgumentException e) { if(authenticated) throw e; reject(response,401); }
  }

  private AuthContext resolve(Identity identity, Binding b) {
    if ("STUDENT".equals(b.role())) {
      return students.findById(b.studentId()).filter(s->Boolean.TRUE.equals(s.getActive()) && b.academyId().equals(s.getAcademyId()))
          .map(s->new AuthContext(AuthContext.PrincipalType.STUDENT,s.getId(),s.getAcademyId(),ReaRole.PARENT,s.getName())).orElse(null);
    }
    return members.findById(b.memberId()).filter(m->Boolean.TRUE.equals(m.getActive()) && b.academyId().equals(m.getAcademyId()))
        .filter(m->m.getRole()==ReaRole.TEACHER || m.getRole()==ReaRole.ACADEMY_OWNER)
        // An ordinary tutor may have owner scope only for their actual BuildIt-owned academy.
        .filter(m->!"TUTOR".equals(identity.role()) || m.getRole()!=ReaRole.ACADEMY_OWNER || b.builditAcademyId().equals(identity.ownerAcademyId()))
        .map(m->new AuthContext(AuthContext.PrincipalType.MEMBER,m.getId(),m.getAcademyId(),m.getRole(),m.getName())).orElse(null);
  }

  static boolean allowed(String path,String method,Binding b) {
    if (path.equals("/api/rea/integration/context")) return method.equals("GET");
    if (path.equals("/api/rea/integration/teachers")) return method.equals("GET") && !"STUDENT".equals(b.role());
    if (!path.startsWith("/api/rea/")) return method.equals("GET") && (path.matches("/screens/[A-Za-z0-9_-]+\\.html") || path.matches("/components/[A-Za-z0-9_-]+\\.(js|css)"));
    String base="/api/rea/"+b.academyId();
    if ("STUDENT".equals(b.role())) return method.equals("GET") && (path.equals(base+"/students/"+b.studentId()) || path.equals(base+"/attendance/student/"+b.studentId()));
    return path.matches(base+"/(students|classes|attendance|dashboard/stats|timetable|consultations|notices)(/.*)?");
  }

  public static String sign(String secret,String method,String target,String assertion,byte[] body) throws GeneralSecurityException {
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal((method+"\n"+target+"\n"+assertion+"\n"+digest).getBytes(StandardCharsets.UTF_8)));
  }
  private static void reject(HttpServletResponse response,int status) throws IOException {
    response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); response.setHeader("Cache-Control","no-store");
    response.getWriter().write("{\"message\":\"AcademyCraft 인증 또는 학원 연결을 확인하세요.\"}");
  }
  public record Identity(Long userId,String role,Long ownerAcademyId,long issuedAt,String nonce) {}
  public record Binding(Long userId,String role,Long builditAcademyId,Long academyId,Long memberId,Long studentId) {}
  static class CachedRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    CachedRequest(HttpServletRequest request,byte[] body) { super(request); this.body=body; }
    @Override public ServletInputStream getInputStream() {
      ByteArrayInputStream in=new ByteArrayInputStream(body);
      return new ServletInputStream() {
        public int read(){return in.read();}
        public boolean isFinished(){return in.available()==0;}
        public boolean isReady(){return true;}
        public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
      };
    }
    @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
  }
}
