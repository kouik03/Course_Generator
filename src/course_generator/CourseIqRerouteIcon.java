package course_generator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** Two diverging paths; the straight route is crossed out. */
public final class CourseIqRerouteIcon implements Icon {
    private final int size;
    private CourseIqRerouteIcon(int size) { this.size=size; }
    public static Icon create(int size) { return new CourseIqRerouteIcon(size); }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D d=(Graphics2D)g.create();
        try {
            d.translate(x,y);
            d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            d.scale(size/24.0,size/24.0);
            d.setStroke(new BasicStroke(2.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            d.setColor(new Color(55,110,170));
            d.drawLine(3,21,11,13);d.drawLine(11,13,11,3);
            d.drawLine(11,13,20,13);d.drawLine(20,13,20,3);
            d.setColor(new Color(195,55,55));
            d.drawLine(15,3,23,11);d.drawLine(23,3,15,11);
        } finally { d.dispose(); }
    }
}
