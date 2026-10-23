package org.celanwang.mio.goals;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class GoalProgramTest {
    GoalProgram.Policy policy(){return new GoalProgram.Policy(false,Long.MAX_VALUE,5,60,1,Set.of("domain"));}
    @Test void aPreparationStepUsesOnlyItsDeclaredUserFields(){
        var step=new GoalProgram.Step("first","domain","compare",List.of("place"),List.of(),"report",List.of());
        var plan=new GoalProgram("g",1,1,Map.of("place",new GoalProgram.Field("上海",1),"private",new GoalProgram.Field("secret",1)),List.of(step),policy(),Map.of("first",1L));
        assertThat(plan.task(step,List.of()).objective()).contains("上海").doesNotContain("secret");assertThat(plan.task(step,List.of()).scope()).isEqualTo(org.celanwang.mio.orchestration.TaskScope.PREPARE);
    }
    @Test void cyclicDependenciesAreRejectedBeforeSaving(){
        var a=new GoalProgram.Step("a","domain","a",List.of(),List.of("b"),"report",List.of());var b=new GoalProgram.Step("b","domain","b",List.of(),List.of("a"),"report",List.of());
        assertThatThrownBy(()->new GoalProgram("g",1,1,Map.of(),List.of(a,b),policy(),Map.of("a",1L,"b",1L))).isInstanceOf(IllegalArgumentException.class);
    }
}
