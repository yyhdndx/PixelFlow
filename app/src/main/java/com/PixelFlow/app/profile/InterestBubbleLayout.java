package com.PixelFlow.app.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InterestBubbleLayout {
    public static final class Circle {
        public final int index;
        public final float x, y, diameter;
        Circle(int index, float x, float y, float diameter) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.diameter = diameter;
        }
    }

    public static List<Circle> arrange(float width, double[] weights) {
        List<Circle> circles = new ArrayList<>();
        if (width <= 0 || weights.length == 0) return circles;
        double maximum = 0;
        for (double weight : weights) if (Double.isFinite(weight) && weight > maximum) maximum = weight;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> valid(weights[i])).reversed());
        float largest = Math.min(116, width);
        float smallest = Math.min(64, largest);
        float anchorY = largest;
        for (int index : order) {
            double ratio = maximum > 0 ? valid(weights[index]) / maximum : 1;
            float diameter = smallest + (largest - smallest) * (float) Math.sqrt(ratio);
            float radius = diameter / 2;
            float bestX = width / 2, bestY = radius;
            double bestDistance = Double.MAX_VALUE;
            float limit = (circles.size() + 2) * (largest + 8);
            for (float y = radius; y <= limit; y += 4) {
                for (float x = radius; x <= width - radius + 0.01f; x += 4) {
                    boolean fits = true;
                    for (Circle other : circles) {
                        float separation = radius + other.diameter / 2 + 8;
                        if (Math.hypot(x - other.x, y - other.y) < separation) { fits = false; break; }
                    }
                    double distance = Math.pow(x - width / 2, 2) + Math.pow(y - anchorY, 2);
                    if (fits && distance < bestDistance) {
                        bestDistance = distance;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            circles.add(new Circle(index, bestX, bestY, diameter));
        }
        float top = Float.MAX_VALUE;
        for (Circle circle : circles) top = Math.min(top, circle.y - circle.diameter / 2);
        List<Circle> normalized = new ArrayList<>();
        for (Circle circle : circles) normalized.add(new Circle(circle.index, circle.x, circle.y - top, circle.diameter));
        return normalized;
    }

    private static double valid(double weight) {
        return Double.isFinite(weight) && weight > 0 ? weight : 0;
    }
}
