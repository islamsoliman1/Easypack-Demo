package com.easyback.identity;

import com.easyback.catalog.CatalogData.*;
import com.easyback.catalog.CatalogService;
import com.easyback.orders.OrderData;
import com.easyback.orders.OrderService;
import com.easyback.warehouse.*;
import com.easyback.assembly.*;
import com.easyback.operations.*;
import com.easyback.defects.DefectService;
import com.easyback.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic, transactional first-boot data. Never imports or resets an existing database. */
@Component
@Profile("demo")
public class DemoBootstrap implements ApplicationRunner {
    public static final UUID VISITOR = UUID.fromString("de000000-0000-4000-8000-000000000001");
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final CatalogService catalog;
    private final OrderService orders;
    private final StockService stock;
    private final RawService raw;
    private final AssemblyService assembly;
    private final OperationsService operations;
    private final TemplateService templates;
    private final DefectService defects;
    private final WorkService work;

    public DemoBootstrap(IdentityStore store, PasswordEncoder passwords, CatalogService catalog,
        OrderService orders, StockService stock, RawService raw, AssemblyService assembly,
        OperationsService operations, TemplateService templates, DefectService defects, WorkService work) {
        this.store=store; this.passwords=passwords; this.catalog=catalog; this.orders=orders;
        this.stock=stock; this.raw=raw; this.assembly=assembly; this.operations=operations;
        this.templates=templates; this.defects=defects; this.work=work;
    }

    public static List<String> visitorPermissions() {
        return Permissions.ALL.stream().filter(p -> !Permissions.ADMIN.contains(p)
            || p.equals("users.view") || p.equals("roles.view")).filter(p -> !p.equals("files.manage")).toList();
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        store.guard();
        if (store.db().sql("SELECT seeded FROM demo_installation WHERE id=1 FOR UPDATE").query(Boolean.class).single()) return;
        if (store.db().sql("SELECT count(*) FROM users").query(Long.class).single()!=0)
            throw new IllegalStateException("Demo seeding requires an empty database.");
        UUID role=UUID.randomUUID();
        store.db().sql("INSERT INTO roles(id,name,description) VALUES (:id,'زائر الديمو','تجربة عمليات المصنع ببيانات وهمية')").param("id",role).update();
        for (var permission:visitorPermissions()) store.db().sql("INSERT INTO role_permissions(role_id,permission) VALUES (:role,:permission)")
            .param("role",role).param("permission",permission).update();
        // Unknown random password: visitors enter only through the demo session endpoint.
        store.db().sql("INSERT INTO users(id,email,display_name,password_hash,must_change_password) VALUES (:id,'visitor@demo.example','زائر الديمو',:hash,false)")
            .param("id",VISITOR).param("hash",passwords.encode(UUID.randomUUID().toString())).update();
        store.db().sql("INSERT INTO user_roles(user_id,role_id) VALUES (:user,:role)").param("user",VISITOR).param("role",role).update();
        work.save(new WorkData.CalendarInput(work.calendar().version(), List.of(1,2,3,4,6,7),
            List.of(new WorkData.Break(LocalTime.of(12,0),LocalTime.of(12,30))),true),VISITOR);

        var filler=catalog.saveModel(null,new ModelInput("DEMO-FILL-200","ماكينة تعبئة أوتوماتيكية","موديل تجريبي لتعبئة العبوات",true,0L),VISITOR);
        var packer=catalog.saveModel(null,new ModelInput("DEMO-PACK-100","ماكينة تغليف حراري","موديل تجريبي للتغليف",true,0L),VISITOR);
        catalog.saveModel(null,new ModelInput("DEMO-LABEL-50","ماكينة لصق ملصقات","موديل جاهز لإنشاء قائمة مكونات جديدة",true,0L),VISITOR);
        var motor=piece("DEMO-MOTOR","محرك السير",35);
        var sensor=piece("DEMO-SENSOR","حساس ضوئي",15);
        var control=piece("DEMO-CONTROL","لوحة التحكم",45);
        var valve=piece("DEMO-VALVE","صمام التعبئة",20);
        var sheet=catalog.savePart(null,new PartInput("DEMO-STEEL","لوح ستانلس خام","خامة تجريبية للقص",
            Kind.MEASURED_STOCK,Shape.RECTANGULAR,StockUnit.AREA,bd(2000),bd(1000),null,BigDecimal.ZERO,true,0L),VISITOR);
        var bracket=catalog.savePart(null,new PartInput("DEMO-BRACKET","قاعدة تثبيت ستانلس","قطعة تحتاج تجهيزًا بالقص",
            Kind.SERIALIZED_PIECE,Shape.RECTANGULAR,StockUnit.PIECE,bd(200),bd(100),null,bd(10),true,0L),VISITOR);
        raw.receive(new RawData.Receipt(sheet.id(),List.of("DEMO-RAW-01","DEMO-RAW-02"),bd(2000),bd(1000),"رف الخامات A","استلام تجريبي","بيانات وهمية"),VISITOR);
        for (var part:List.of(motor,sensor,control,valve)) stock.receive(new StockData.Receipt(part.id(),
            IntStream.rangeClosed(1,16).mapToObj(n->part.code()+"-"+String.format("%03d",n)).toList(),
            "مخزن القطع A","رصيد افتتاحي تجريبي","قطع وهمية لا تمثل مخزونًا حقيقيًا"),VISITOR);
        var fillBom=bom(filler,List.of(motor,sensor,valve));
        var packBom=bom(packer,List.of(motor,sensor,control));
        var checks=List.of(new OperationsData.CheckInput("فحص السلامة","عمل زر الإيقاف الطارئ"),
            new OperationsData.CheckInput("جودة التشغيل","تشغيل مستقر دون تسريب أو اهتزاز"));
        templates.save(null,new TemplateController.Input(0L,"فحص التشغيل الأساسي",true,checks),VISITOR);

        createOrder(1,filler,fillBom,"طلب جديد للتجربة",0,checks);
        var shortage=createOrder(2,filler,fillBom,"تعبئة — تجهيز النواقص",1,checks,bracket);
        var coverage=stock.orderStock(shortage,VISITOR,true);
        for (var requirement:coverage.requirements()) if(requirement.missing()>0)
            stock.requestPreparation(shortage,new StockData.PreparationInput(stock.orderStock(shortage,VISITOR,true).version(),requirement.requirementId(),"تجهيز القاعدة من خامات الستانلس"),VISITOR);
        createOrder(3,packer,packBom,"تغليف — قيد التجميع",2,checks);
        createOrder(4,filler,fillBom,"تعبئة — جاهز للتجربة",3,checks);
        createOrder(5,packer,packBom,"تغليف — مكتمل وتم التسليم",4,checks);
        createOrder(6,filler,fillBom,"تعبئة — معالجة نتيجة التشغيل",5,checks);
        var defectOrder=createOrder(7,packer,packBom,"تغليف — فحص قطعة معيبة",2,checks);
        var pending=assembly.tasks(VISITOR,true,defectOrder).stream().filter(t->t.status().equals("PENDING") && t.pieceId()!=null).findFirst().orElseThrow();
        defects.report(pending.id(),new AssemblyData.Removal(pending.version(),"حساس تجريبي لا يستجيب أثناء الفحص"),VISITOR,true);
        store.db().sql("UPDATE demo_installation SET seeded=true WHERE id=1").update();
        store.audit(VISITOR,"DEMO_INITIALIZED",VISITOR);
    }

    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }
    private Part piece(String code,String name,int minutes) {
        return catalog.savePart(null,new PartInput(code,name,"قطعة تجريبية",Kind.SERIALIZED_PIECE,Shape.STANDARD,
            StockUnit.PIECE,null,null,null,bd(minutes),true,0L),VISITOR);
    }
    private Bom bom(Model model,List<Part> parts) {
        var bom=catalog.createDraft(model.id(),new DraftInput(null),VISITOR);
        int position=0;
        for(var part:parts) bom=catalog.saveItem(bom.id(),null,new ItemInput("P"+(++position),part.id(),1,part.defaultInstallMinutes(),"",bom.version()),VISITOR);
        return catalog.publish(bom.id(),bom.version(),VISITOR);
    }
    private UUID createOrder(int number,Model model,Bom modelBom,String title,int stage,List<OperationsData.CheckInput> checks,Part... extras) {
        var machine=catalog.saveMachine(null,new MachineInput("DEMO-MACHINE-0"+number,model.id(),model.name()+" "+number,"صالة العرض","ماكينة تجريبية",true,0L),VISITOR);
        var bom=catalog.createMachineDraft(machine.id(),new DraftInput(modelBom.id()),VISITOR);
        for(var part:extras) bom=catalog.saveItem(bom.id(),null,new ItemInput("EXTRA",part.id(),2,part.defaultInstallMinutes(),"",bom.version()),VISITOR);
        bom=catalog.publish(bom.id(),bom.version(),VISITOR);
        var due=Instant.now().plusSeconds(number*86400L);
        var order=orders.save(null,new OrderData.Input("DEMO-ORDER-00"+number,title,machine.id(),bom.id(),VISITOR,due,"بيانات عرض وهمية — يمكن تجربة العمليات",0L),VISITOR);
        if(stage==0) return order.id();
        order=orders.release(order.id(),order.version(),VISITOR);
        for(var task:assembly.tasks(VISITOR,true,order.id())) assembly.assign(task.id(),new AssemblyData.Assignment(task.version(),VISITOR,"تكليف تجريبي"),VISITOR);
        if(stage==1) return order.id();
        var stockState=stock.orderStock(order.id(),VISITOR,true);
        stock.issue(order.id(),new StockData.Selection(stockState.version(),stockState.pieces().stream().filter(p->p.status().equals("RESERVED")).map(StockData.Piece::id).toList(),"صرف للتجميع",VISITOR),VISITOR);
        var tasks=assembly.tasks(VISITOR,true,order.id());
        int count=stage==2?1:tasks.size();
        for(int i=0;i<count;i++) {
            var task=assembly.start(tasks.get(i).id(),new AssemblyData.Action(tasks.get(i).version(),"بدء تجميع تجريبي"),VISITOR,true).task();
            assembly.install(task.id(),new AssemblyData.Action(task.version(),"تم التركيب والفحص"),VISITOR,true);
        }
        if(stage<4) return order.id();
        var ready=operations.detail(order.id()).order();
        var trial=operations.start(order.id(),new OperationsData.Start(ready.version(),ready.configurationVersion(),checks,"تجربة تشغيل توضيحية"),VISITOR).trials().getFirst();
        var outcome=stage==5?OperationsData.Outcome.FAILED:OperationsData.Outcome.PASSED;
        var result=operations.finish(trial.id(),new OperationsData.Finish(trial.version(),outcome,"نتيجة تجريبية",
            trial.checks().stream().map(c->new OperationsData.Result(c.id(),stage==5?OperationsData.CheckResult.FAIL:OperationsData.CheckResult.PASS,
                stage==5?"يحتاج ضبطًا وإعادة تجربة":"مطابق")).toList()),VISITOR);
        if(stage==4) operations.deliver(order.id(),new OperationsData.Action(result.order().version(),"تسليم تجريبي مكتمل"),VISITOR);
        return order.id();
    }
}
