package com.PixelFlow.app.profile;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class InterestBubbleLayoutTest {
    @Test public void emptyAndSingleInterestStayWithinCard() {
        assertTrue(InterestBubbleLayout.arrange(280, new double[0]).isEmpty());
        List<InterestBubbleLayout.Circle> circles = InterestBubbleLayout.arrange(280, new double[]{1});
        assertEquals(1, circles.size());
        assertEquals(140, circles.get(0).x, 4);
        verify(280, circles);
    }

    @Test public void narrowAndExtremeInputsNeverOverlapOrOverflow() {
        for (float width : new float[]{40, 180, 280, 400}) {
            List<InterestBubbleLayout.Circle> circles = InterestBubbleLayout.arrange(width,
                    new double[]{Double.NaN, Double.POSITIVE_INFINITY, -2, 0, 0.01, 1, Double.MAX_VALUE});
            assertEquals(7, circles.size());
            verify(width, circles);
        }
    }

    @Test public void equalWeightsAndRepeatedLayoutAreStable() {
        double[] weights = {1, 1, 1, 1, 1, 1, 1};
        List<InterestBubbleLayout.Circle> first = InterestBubbleLayout.arrange(260, weights);
        List<InterestBubbleLayout.Circle> second = InterestBubbleLayout.arrange(260, weights);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(0).diameter, first.get(i).diameter, 0);
            assertEquals(first.get(i).x, second.get(i).x, 0);
            assertEquals(first.get(i).y, second.get(i).y, 0);
        }
        verify(260, first);
    }

    private void verify(float width, List<InterestBubbleLayout.Circle> circles) {
        for (InterestBubbleLayout.Circle a : circles) {
            assertTrue(a.x - a.diameter / 2 >= -0.01);
            assertTrue(a.x + a.diameter / 2 <= width + 0.01);
            assertTrue(a.y - a.diameter / 2 >= -0.01);
            for (InterestBubbleLayout.Circle b : circles) {
                if (a == b) continue;
                assertTrue(Math.hypot(a.x - b.x, a.y - b.y) + 0.01 >= (a.diameter + b.diameter) / 2 + 8);
            }
        }
    }
}
