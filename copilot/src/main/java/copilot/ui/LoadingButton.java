package copilot.ui;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import copilot.CopilotLoadingStateNotifier;
import javax.swing.*;
import javax.swing.plaf.ButtonUI;
import java.awt.*;
import java.lang.reflect.Method;
public class LoadingButton extends JButton {
    private static final Color PULSE_COLOR = new Color(86, 156, 214);
    private boolean loading    = false;
    private float   pulsePhase = 0f;
    private Timer   pulseTimer;
    private MessageBusConnection busConnection;
    public LoadingButton(String text) {
        super(text);
        // Prevent Swing from painting a rectangular background that overflows
        // the rounded border drawn by the IDE's Look-and-Feel.
        setOpaque(false);
        setContentAreaFilled(false);
    }
    @Override
    public void addNotify() {
        super.addNotify();
        busConnection = ApplicationManager.getApplication().getMessageBus().connect();
        busConnection.subscribe(CopilotLoadingStateNotifier.TOPIC,
                new CopilotLoadingStateNotifier() {
                    @Override public void loadingStarted() {
                        SwingUtilities.invokeLater(() -> setLoading(true));
                    }
                    @Override public void loadingStopped() {
                        SwingUtilities.invokeLater(() -> setLoading(false));
                    }
                });
    }
    @Override
    public void removeNotify() {
        stopPulse();
        if (busConnection != null) {
            busConnection.disconnect();
            busConnection = null;
        }
        super.removeNotify();
    }
    public void setLoading(boolean loading) {
        if (this.loading == loading) return;
        this.loading = loading;
        if (loading) {
            pulsePhase = 0f;
            pulseTimer = new Timer(40, e -> { pulsePhase += 0.14f; repaint(); });
            pulseTimer.start();
        } else {
            stopPulse();
            repaint();
        }
    }
    public boolean isLoading() { return loading; }
    private void stopPulse() {
        loading = false;
        if (pulseTimer != null) { pulseTimer.stop(); pulseTimer = null; }
    }
    @Override
    protected void paintComponent(Graphics g) {
        int arc = resolveButtonArc(getUI(), this);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Paint the button background clipped to the rounded shape
        ButtonModel model = getModel();
        if (model.isPressed()) {
            g2.setColor(getBackground().darker());
        } else if (model.isRollover()) {
            g2.setColor(getBackground().brighter());
        } else {
            g2.setColor(getBackground());
        }
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }
    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (!loading) return;
        int arc = resolveButtonArc(getUI(), this);
        int bw  = Math.max(1, JBUI.scale(1));
        float intensity = (float)(0.5 + 0.5 * Math.sin(pulsePhase));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int x = bw, y = bw;
        int w = getWidth()  - 2 * bw;
        int h = getHeight() - 2 * bw;
        int innerArc = Math.max(0, arc - bw);
        // Translucent fill
        g2.setColor(pulse((int)(18 + 28 * intensity)));
        g2.fillRoundRect(x, y, w, h, innerArc, innerArc);
        // Crisp inner border ring
        g2.setColor(pulse((int)(110 + 145 * intensity)));
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawRoundRect(x, y, w - 1, h - 1, innerArc, innerArc);
        // Soft outer halo
        g2.setColor(pulse((int)(30 + 50 * intensity)));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.dispose();
    }
    private static Color pulse(int alpha) {
        return new Color(PULSE_COLOR.getRed(), PULSE_COLOR.getGreen(),
                PULSE_COLOR.getBlue(), Math.min(255, alpha));
    }
    /**
     * Reads the button corner arc from the IDE UI delegate via reflection so the
     * overlay corners always match the theme exactly.
     * Tries getArc(Component) then getArc(), falls back to JBUI.scale(8).
     */
    private static int resolveButtonArc(ButtonUI ui, Component c) {
        if (ui == null) return JBUI.scale(8);
        for (Class<?> cls = ui.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod("getArc", Component.class);
                m.setAccessible(true);
                return (int) m.invoke(ui, c);
            } catch (Exception ignored) {}
            try {
                Method m = cls.getDeclaredMethod("getArc");
                m.setAccessible(true);
                return (int) m.invoke(ui);
            } catch (Exception ignored) {}
        }
        return JBUI.scale(8);
    }
}