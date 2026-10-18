package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionPolicyTest {
    @Test public void accessibilityReductionOverridesEveryChoice() {
        for (int i=0; i<=4; i++) assertEquals(0f,MotionPolicy.scale(i,0f),0f);
        assertEquals(2f,MotionPolicy.scale(4,.5f),0f);
    }
    @Test public void corruptSettingsCannotCreateUnboundedOrNegativeAnimationClocks() {
        assertEquals(0,MotionPolicy.mode(-1)); assertEquals(0,MotionPolicy.mode(99));
        assertEquals(.8f,MotionPolicy.scale(99,Float.NaN),0f);
        assertEquals(.8f,MotionPolicy.scale(0,Float.POSITIVE_INFINITY),0f);
        assertEquals(40f,MotionPolicy.scale(4,1000f),0f);
        assertEquals(.8f,MotionPolicy.multiplier(0),0f); assertEquals(1f,MotionPolicy.multiplier(1),0f); assertEquals(3f,MotionPolicy.multiplier(3),0f);
    }
}
