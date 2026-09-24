package com.argus.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

/**
 * A minimal, zero-dependency icon set built from plain JavaFX shape nodes (no icon font, no
 * SVG library, no image assets) -- the simplest approach that keeps the cybersecurity/SOC
 * aesthetic consistent everywhere it's used. Every returned {@link Node} is unstyled by fill
 * color: callers apply {@code .nav-icon} (or an equivalent stroke-color style class) so active
 * / hover coloring flows through the same centralized theme tokens as everything else.
 *
 * Each icon is a fixed 16x16 logical box, stroke-only ("outline" style), so the whole set reads
 * as one consistent family regardless of which shapes compose a given glyph.
 */
final class Icons {

    private static final double SIZE = 16;

    private Icons() {
    }

    /** The sidebar/nav icon for {@code name}, or a small placeholder dot if unrecognized. */
    static Node forName(String name) {
        return switch (name) {
            case "dashboard" -> dashboard();
            case "scan" -> scan();
            case "findings" -> findings();
            case "annotations" -> annotations();
            case "tags" -> tags();
            case "scheduled-scans" -> scheduledScans();
            case "reports" -> reports();
            case "settings" -> settings();
            case "diff" -> diff();
            case "charts" -> charts();
            case "graph" -> graph();
            case "notifications" -> notifications();
            default -> placeholder();
        };
    }

    /** The Argus watchful-eye brand mark: an outer eye shape with an iris and a highlight. */
    static Node eye(double scale) {
        Ellipse outer = outline(new Ellipse(scale * 12, scale * 7));
        outer.setStrokeWidth(scale * 1.6);
        Circle iris = outline(new Circle(scale * 4.2));
        iris.setStrokeWidth(scale * 1.4);
        Circle pupil = new Circle(scale * 1.6);
        pupil.getStyleClass().add("eye-pupil");
        Group group = new Group(outer, iris, pupil);
        group.getStyleClass().add("argus-eye");
        return group;
    }

    private static Node dashboard() {
        double gap = 2;
        double cell = (SIZE - gap) / 2 - gap / 2;
        Group group = new Group();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                Rectangle rect = outline(new Rectangle(cell, cell));
                rect.setArcWidth(3);
                rect.setArcHeight(3);
                rect.setX(col * (cell + gap));
                rect.setY(row * (cell + gap));
                group.getChildren().add(rect);
            }
        }
        return framed(group);
    }

    private static Node scan() {
        Circle lens = outline(new Circle(4.6));
        lens.setCenterX(6.5);
        lens.setCenterY(6.5);
        Line handle = strokeLine(10, 10, 14.5, 14.5);
        return framed(new Group(lens, handle));
    }

    private static Node findings() {
        Polygon shield = outline(new Polygon(
                8, 1,
                14.5, 3.5,
                14.5, 8,
                8, 15,
                1.5, 8,
                1.5, 3.5));
        return framed(new Group(shield));
    }

    private static Node annotations() {
        Rectangle note = outline(new Rectangle(1.5, 1.5, 13, 13));
        note.setArcWidth(3);
        note.setArcHeight(3);
        Line l1 = strokeLine(4, 6, 12, 6);
        Line l2 = strokeLine(4, 9, 12, 9);
        Line l3 = strokeLine(4, 12, 9, 12);
        return framed(new Group(note, l1, l2, l3));
    }

    private static Node tags() {
        Polygon tag = outline(new Polygon(
                1.5, 1.5,
                9, 1.5,
                14.5, 7,
                8, 14.5,
                1.5, 8));
        Circle hole = outline(new Circle(4.5, 4.5, 1.3));
        return framed(new Group(tag, hole));
    }

    private static Node scheduledScans() {
        Circle face = outline(new Circle(8, 8, 6.5));
        Line hourHand = strokeLine(8, 8, 8, 4.2);
        Line minuteHand = strokeLine(8, 8, 11, 9.5);
        return framed(new Group(face, hourHand, minuteHand));
    }

    private static Node reports() {
        Rectangle page = outline(new Rectangle(2.5, 1.5, 9, 13));
        page.setArcWidth(2);
        page.setArcHeight(2);
        Polygon fold = outline(new Polygon(11.5, 1.5, 14.5, 4.5, 11.5, 4.5));
        Line l1 = strokeLine(5, 7, 10, 7);
        Line l2 = strokeLine(5, 10, 10, 10);
        return framed(new Group(page, fold, l1, l2));
    }

    private static Node settings() {
        Polygon body = outline(new Polygon(
                8, 1, 13.5, 4.2, 13.5, 11.8, 8, 15, 2.5, 11.8, 2.5, 4.2));
        Circle hub = outline(new Circle(8, 8, 3));
        return framed(new Group(body, hub));
    }

    private static Node diff() {
        Line left = strokeLine(2, 5, 8, 5);
        Line leftHead = strokeLine(5, 2, 2, 5);
        Line leftHead2 = strokeLine(5, 8, 2, 5);
        Line right = strokeLine(8, 11, 14, 11);
        Line rightHead = strokeLine(11, 8, 14, 11);
        Line rightHead2 = strokeLine(11, 14, 14, 11);
        return framed(new Group(left, leftHead, leftHead2, right, rightHead, rightHead2));
    }

    private static Node charts() {
        Line baseline = strokeLine(1.5, 14.5, 14.5, 14.5);
        Rectangle bar1 = outline(new Rectangle(3, 9, 2.5, 5.5));
        Rectangle bar2 = outline(new Rectangle(7, 5, 2.5, 9.5));
        Rectangle bar3 = outline(new Rectangle(11, 7, 2.5, 7.5));
        return framed(new Group(baseline, bar1, bar2, bar3));
    }

    private static Node graph() {
        Circle center = outline(new Circle(8, 8, 2));
        Circle topLeft = outline(new Circle(2.5, 3, 1.6));
        Circle topRight = outline(new Circle(13.5, 3, 1.6));
        Circle bottom = outline(new Circle(8, 14, 1.6));
        Line l1 = strokeLine(6.3, 6.7, 3.5, 4);
        Line l2 = strokeLine(9.7, 6.7, 12.5, 4);
        Line l3 = strokeLine(8, 10, 8, 12.4);
        return framed(new Group(l1, l2, l3, center, topLeft, topRight, bottom));
    }

    private static Node notifications() {
        Polygon bell = outline(new Polygon(
                8, 1.5,
                12.5, 5, 12.5, 9.5, 14.5, 12.5,
                1.5, 12.5, 3.5, 9.5, 3.5, 5));
        Line clapper = strokeLine(6.5, 14, 9.5, 14);
        return framed(new Group(bell, clapper));
    }

    private static Node placeholder() {
        return framed(new Group(outline(new Circle(8, 8, 3))));
    }

    /** The login password-visibility toggle glyph: a plain open-eye outline when {@code masked}
     *  (the password is hidden -- clicking reveals it), with a diagonal slash added when not
     *  {@code masked} (the password is shown -- clicking hides it again). Distinct from
     *  {@link #eye(double)} (the brand mark, which has an iris highlight) -- this is a plain
     *  16x16 nav-style icon. */
    static Node eyeToggle(boolean masked) {
        Ellipse outer = outline(new Ellipse(8, 5.5));
        outer.setCenterX(8);
        outer.setCenterY(8);
        Circle pupil = outline(new Circle(8, 8, 1.8));
        Group group = new Group(outer, pupil);
        if (!masked) {
            group.getChildren().add(strokeLine(2, 13, 14, 3));
        }
        return framed(group);
    }

    private static Node framed(Group group) {
        group.getStyleClass().add("nav-icon");
        return group;
    }

    private static <T extends javafx.scene.shape.Shape> T outline(T shape) {
        shape.setFill(javafx.scene.paint.Color.TRANSPARENT);
        shape.getStyleClass().add("nav-icon");
        return shape;
    }

    private static Line strokeLine(double startX, double startY, double endX, double endY) {
        Line line = new Line(startX, startY, endX, endY);
        line.getStyleClass().add("nav-icon");
        return line;
    }
}
