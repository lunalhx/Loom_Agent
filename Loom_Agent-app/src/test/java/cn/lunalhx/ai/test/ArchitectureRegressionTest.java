package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
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
                    .or().haveFullyQualifiedName(SubAgentCoordinator.class.getName())
                    .or().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .or().haveFullyQualifiedName(ModelCallNode.class.getName())
                    .or().haveFullyQualifiedName(ToolDispatchNode.class.getName())
                    .or().haveFullyQualifiedName(RenderPromptNode.class.getName())
                    .should(haveConstructorsWithAtMost5Params());

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
                    .resideInAnyPackage("cn.lunalhx.ai.domain.agent.adapter.port..")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("cn.lunalhx.ai.domain.model.adapter.port.ModelGateway");

    // ---- Rule 6: ContextWindowManager 不得直接依赖具体基础设施实现 ----

    @ArchTest
    public static final ArchRule context_window_manager_must_not_depend_on_infrastructure =
            noClasses().that().haveFullyQualifiedName(ContextWindowManager.class.getName())
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.infrastructure..")
                    .orShould().dependOnClassesThat()
                    .haveNameMatching(".*HashUtil.*");

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
}
