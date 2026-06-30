package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.runner.RunWith;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@RunWith(ArchUnitRunner.class)
@AnalyzeClasses(packages = "cn.lunalhx.ai")
public class ArchitectureRegressionTest {

    // ---- Rule 1: DefaultAgentLoopService 只能由 AgentLoopFactory 构造 ----

    @ArchTest
    public static final ArchRule agent_loop_service_constructors_are_package_private =
            classes().that().areAssignableTo(DefaultAgentLoopService.class)
                    .should(onlyHaveNonPublicConstructors());

    // ---- Rule 2: DefaultAgentLoopService 不得依赖具体 Node 类 ----

    @ArchTest
    public static final ArchRule agent_loop_service_must_not_depend_on_concrete_nodes =
            noClasses().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.domain.agent.flow.node..");

    // ---- Rule 3: 目标类最多只有一个公共构造器（ContextWindowManager 允许两个） ----

    @ArchTest
    public static final ArchRule target_classes_have_at_most_one_public_constructor =
            classes().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .or().haveFullyQualifiedName(SubAgentCoordinator.class.getName())
                    .or().haveFullyQualifiedName(ModelCallNode.class.getName())
                    .or().haveFullyQualifiedName(ToolDispatchNode.class.getName())
                    .or().haveFullyQualifiedName(RenderPromptNode.class.getName())
                    .should(haveAtMostOnePublicConstructor());

    @ArchTest
    public static final ArchRule context_window_manager_allows_two_constructors =
            classes().that().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .should(haveAtMostTwoPublicConstructors());

    // ---- Rule 4: 目标类构造参数不得超过 5 个 ----

    @ArchTest
    public static final ArchRule target_constructors_have_at_most_5_params =
            classes().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .or().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .or().haveFullyQualifiedName(ModelCallNode.class.getName())
                    .or().haveFullyQualifiedName(ToolDispatchNode.class.getName())
                    .or().haveFullyQualifiedName(RenderPromptNode.class.getName())
                    .should(haveConstructorsWithAtMost5Params());

    @ArchTest
    public static final ArchRule sub_agent_coordinator_constructors_have_at_most_6_params =
            classes().that().haveFullyQualifiedName(SubAgentCoordinator.class.getName())
                    .should(haveConstructorsWithAtMost6Params());

    // ---- Rule 5: SubAgentCoordinator 不得依赖并发/基础设施类 ----

    @ArchTest
    public static final ArchRule sub_agent_coordinator_must_not_depend_on_infrastructure =
            noClasses().that().haveFullyQualifiedName(SubAgentCoordinator.class.getName())
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.concurrent.Semaphore")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("reactor..")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.fasterxml.jackson.databind.JsonNode")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("cn.lunalhx.ai.domain.model.adapter.port.ModelGateway")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.infrastructure..");

    // ---- Rule 6: ContextWindowManager 不得直接依赖具体基础设施实现 ----

    @ArchTest
    public static final ArchRule context_window_manager_must_not_depend_on_infrastructure =
            noClasses().that().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.infrastructure..")
                    .orShould().dependOnClassesThat()
                    .haveNameMatching(".*HashUtil.*");

    // ---- Rule 6b: ContextWindowManager 不得声明名为 noop 的公开静态工厂方法 ----

    @ArchTest
    public static final ArchRule context_window_manager_must_not_have_noop_factory =
            classes().that().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .should(notHavePublicStaticMethodNamedNoop());

    // ---- Rule 7: Controller 不得直接依赖基础设施实现 ----

    @ArchTest
    public static final ArchRule controllers_must_not_depend_on_infrastructure =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.trigger.http..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.infrastructure..")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("cn.lunalhx.ai.domain.model.adapter.port.ModelGateway");

    // ---- Rule 8: 策略/调度器/辅助组件保持 package-private ----

    @ArchTest
    public static final ArchRule strategy_and_helper_classes_are_package_private =
            classes().that().resideInAnyPackage(
                            "cn.lunalhx.ai.domain.agent.service..",
                            "cn.lunalhx.ai.domain.agent.flow..")
                    .and().haveSimpleNameEndingWith("Strategy")
                    .or().haveSimpleNameEndingWith("Scheduler")
                    .or().haveSimpleNameEndingWith("Aggregator")
                    .or().haveSimpleNameEndingWith("Planner")
                    .or().haveSimpleNameEndingWith("Runner")
                    .or().haveSimpleNameEndingWith("Parser")
                    .or().haveSimpleNameEndingWith("Composer")
                    .or().haveSimpleNameEndingWith("Estimator")
                    .or().haveSimpleNameEndingWith("Rewriter")
                    .or().haveSimpleNameEndingWith("Renderer")
                    .and().resideOutsideOfPackage("cn.lunalhx.ai.domain.agent.adapter.port..")
                    .and().resideOutsideOfPackage("cn.lunalhx.ai.infrastructure..")
                    .should().notBePublic();

    // ---- Rule 9: AiRuntimeConfig 不得声明 @Bean ----

    @ArchTest
    public static final ArchRule ai_runtime_config_must_not_declare_bean_methods =
            classes().that().haveFullyQualifiedName("cn.lunalhx.ai.config.AiRuntimeConfig")
                    .should(notHaveBeanAnnotatedMethods());

    // ---- Rule 10: 五个子 AutoConfig 不得相互依赖具体配置类 ----

    @ArchTest
    public static final ArchRule sub_auto_configs_must_not_depend_on_each_other =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.config..")
                    .and().haveSimpleNameEndingWith("AutoConfig")
                    .should().dependOnClassesThat()
                    .haveNameMatching("cn\\.lunalhx\\.ai\\.config\\.\\w+AutoConfig");

    // ---- Rule 11: domain 不得依赖 org.springframework、app 配置包或 app Hook 实现包 ----

    @ArchTest
    public static final ArchRule domain_must_not_depend_on_spring_or_app_concerns =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.config..")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.runtime.hook..");

    // ---- Rule 12: 所有具体 AgentHook 必须位于 app 运行时 Hook 包 ----

    @ArchTest
    public static final ArchRule all_agent_hook_impls_must_reside_in_runtime_hook_package =
            classes().that().areAssignableTo(cn.lunalhx.ai.domain.agent.flow.hook.AgentHook.class)
                    .should().resideInAnyPackage(
                            "cn.lunalhx.ai.runtime.hook..",
                            "cn.lunalhx.ai.domain.agent.flow.hook");

    // ---- Rule 13: 所有具体 AgentHook 必须标注 @Component ----

    @ArchTest
    public static final ArchRule all_agent_hook_impls_must_be_annotated_with_component =
            classes().that().areAssignableTo(cn.lunalhx.ai.domain.agent.flow.hook.AgentHook.class)
                    .and().resideOutsideOfPackage("cn.lunalhx.ai.domain.agent.flow.hook")
                    .should().beAnnotatedWith(org.springframework.stereotype.Component.class);

    // ---- Rule 14: AgentFlowFactory 不得依赖任何具体 Hook 实现 ----

    @ArchTest
    public static final ArchRule agent_flow_factory_must_not_depend_on_concrete_hooks =
            noClasses().that().haveFullyQualifiedName(
                            cn.lunalhx.ai.domain.agent.service.AgentFlowFactory.class.getName())
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.runtime.hook..");

    // ---- 自定义 condition 实现 ----

    private static ArchCondition<JavaClass> onlyHaveNonPublicConstructors() {
        return new ArchCondition<>("have only non-public constructors") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructor constructor : javaClass.getConstructors()) {
                    if (constructor.getModifiers().contains(java.lang.reflect.Modifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(constructor,
                                constructor.getFullName() + " is public"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAtMostOnePublicConstructor() {
        return new ArchCondition<>("have at most one public constructor") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long count = javaClass.getConstructors().stream()
                        .filter(c -> c.getModifiers().contains(java.lang.reflect.Modifier.PUBLIC))
                        .count();
                if (count > 1) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " has " + count + " public constructors (max 1)"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAtMostTwoPublicConstructors() {
        return new ArchCondition<>("have at most two public constructors") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long count = javaClass.getConstructors().stream()
                        .filter(c -> c.getModifiers().contains(java.lang.reflect.Modifier.PUBLIC))
                        .count();
                if (count > 2) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " has " + count + " public constructors (max 2)"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveConstructorsWithAtMost5Params() {
        return new ArchCondition<>("have constructors with at most 5 parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructor constructor : javaClass.getConstructors()) {
                    if (constructor.getRawParameterTypes().size() > 5) {
                        events.add(SimpleConditionEvent.violated(constructor,
                                constructor.getFullName() + " has "
                                        + constructor.getRawParameterTypes().size()
                                        + " parameters (max 5)"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveConstructorsWithAtMost6Params() {
        return new ArchCondition<>("have constructors with at most 6 parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructor constructor : javaClass.getConstructors()) {
                    if (constructor.getRawParameterTypes().size() > 6) {
                        events.add(SimpleConditionEvent.violated(constructor,
                                constructor.getFullName() + " has "
                                        + constructor.getRawParameterTypes().size()
                                        + " parameters (max 6)"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notHavePublicStaticMethodNamedNoop() {
        return new ArchCondition<>("not have a public static method named noop") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.getName().equals("noop")
                            && method.getModifiers().contains(java.lang.reflect.Modifier.PUBLIC)
                            && method.getModifiers().contains(java.lang.reflect.Modifier.STATIC)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " is a public static noop() factory"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notHaveBeanAnnotatedMethods() {
        return new ArchCondition<>("not have methods annotated with @Bean") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith("org.springframework.context.annotation.Bean")) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " is annotated with @Bean — "
                                        + "AiRuntimeConfig must only use @Import"));
                    }
                }
            }
        };
    }
}
