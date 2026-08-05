package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.PromptBuildNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
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

    @ArchTest
    public static final ArchRule agent_loop_service_constructors_are_package_private =
            classes().that().areAssignableTo(DefaultAgentLoopService.class)
                    .should(onlyHaveNonPublicConstructors());

    @ArchTest
    public static final ArchRule agent_loop_service_must_not_depend_on_concrete_nodes =
            noClasses().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.domain.agent.flow.node..");

    @ArchTest
    public static final ArchRule target_classes_have_at_most_one_public_constructor =
            classes().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .or().haveFullyQualifiedName(ModelCallNode.class.getName())
                    .or().haveFullyQualifiedName(ToolDispatchNode.class.getName())
                    .or().haveFullyQualifiedName(PromptBuildNode.class.getName())
                    .should(haveAtMostOnePublicConstructor());

    @ArchTest
    public static final ArchRule target_constructors_have_at_most_5_params =
            classes().that().haveFullyQualifiedName(DefaultAgentLoopService.class.getName())
                    .or().haveFullyQualifiedName(ModelCallNode.class.getName())
                    .or().haveFullyQualifiedName(ToolDispatchNode.class.getName())
                    .or().haveFullyQualifiedName(PromptBuildNode.class.getName())
                    .should(haveConstructorsWithAtMost5Params());

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
                    .and().resideOutsideOfPackage("cn.lunalhx.ai.domain.agent.adapter.port..")
                    .and().resideOutsideOfPackage("cn.lunalhx.ai.infrastructure..")
                    .should().notBePublic();

    @ArchTest
    public static final ArchRule ai_runtime_config_must_not_declare_bean_methods =
            classes().that().haveFullyQualifiedName("cn.lunalhx.ai.config.AiRuntimeConfig")
                    .should(notHaveBeanAnnotatedMethods());

    @ArchTest
    public static final ArchRule no_http_or_legacy_http_concerns_in_codebase =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.boot.web..",
                            "org.springframework.ai..",
                            "org.mybatis..",
                            "org.flywaydb..",
                            "org.sqlite..");

    @ArchTest
    public static final ArchRule no_http_controllers_remain =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai..")
                    .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.Controller")
                    .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping");

    @ArchTest
    public static final ArchRule domain_must_not_depend_on_spring_or_app_concerns =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("cn.lunalhx.ai.config..");

    @ArchTest
    public static final ArchRule no_classes_in_root_service_package =
            noClasses().should().resideInAnyPackage("cn.lunalhx.ai.domain.agent.service");

    @ArchTest
    public static final ArchRule state_classes_must_not_have_jackson_annotations =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.domain.agent.model.state..")
                    .should().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonIgnore")
                    .orShould().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
                    .orShould().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonInclude")
                    .orShould().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonCreator");

    @ArchTest
    public static final ArchRule agent_context_must_not_have_jackson_data_annotation =
            noClasses().that().haveFullyQualifiedName(
                            cn.lunalhx.ai.domain.agent.model.entity.AgentContext.class.getName())
                    .should().beAnnotatedWith("lombok.Data");

    @ArchTest
    public static final ArchRule state_classes_must_not_use_lombok_data =
            noClasses().that().resideInAnyPackage("cn.lunalhx.ai.domain.agent.model.state..")
                    .should().beAnnotatedWith("lombok.Data");

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
