package com.easyback.operations;

import com.easyback.identity.ApiException;
import com.easyback.time.WorkService;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true)
public class TemplateService {
    public record Template(UUID id,String name,boolean active,long version,List<OperationsData.CheckInput> checks) {}
    private final JdbcClient db; private final WorkService work; private final JsonMapper json=JsonMapper.builder().build();
    public TemplateService(JdbcClient db,WorkService work) { this.db=db; this.work=work; }
    public List<Template> list() { return db.sql("SELECT * FROM operations_templates ORDER BY name,id").query((r,n)->new Template(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("active"),r.getLong("version"),Arrays.asList(json.readValue(r.getString("checks"),OperationsData.CheckInput[].class)))).list(); }
    @Transactional
    public Template save(UUID id,TemplateController.Input p,UUID actor) {
        db.sql("SELECT id FROM security_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        db.sql("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        if(id!=null) {
            var version=db.sql("SELECT version FROM operations_templates WHERE id=:id").param("id",id).query(Long.class).optional().orElseThrow(()->new ApiException(404,"NOT_FOUND","Template not found."));
            if(version.longValue()!=p.version().longValue()) throw new ApiException(409,"STALE_VERSION","Template changed.");
        } else if(p.version()!=0) throw new ApiException(409,"STALE_VERSION","New template version must be zero.");
        var checks=p.checks().stream().map(c->new OperationsData.CheckInput(c.label().trim(),c.criterion().trim())).toList();
        UUID target=id==null?UUID.randomUUID():id;
        db.sql("INSERT INTO operations_templates(id,name,checks,active,updated_by) VALUES(:id,:name,CAST(:checks AS JSONB),:active,:actor) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,checks=EXCLUDED.checks,active=EXCLUDED.active,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP,version=operations_templates.version+1")
            .param("id",target).param("name",p.name().trim()).param("checks",json.writeValueAsString(checks)).param("active",p.active()).param("actor",actor).update();
        work.audit(actor,"OPERATIONS_TEMPLATE_SAVED",target); return list().stream().filter(t->t.id().equals(target)).findFirst().orElseThrow();
    }
}
