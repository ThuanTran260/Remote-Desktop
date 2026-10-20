package com.ltm.rd.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The window in which the Server user sees and controls the remote screen. It talks to the
 * application only through the {@link Controller} and the snapshots it is given. Closing it is the
 * same as Disconnect: the capture first asks for everything to be released.
 */
final class ControlFrame extends JFrame {
    private static final int STATS_PERIOD_MS = 1000;

    private final RemotePanel panel = new RemotePanel();
    private final InputCapture capture;
    private final JLabel stats = new JLabel(" ");
    private final JLabel stall = new JLabel(" ");
    private final RateSampler sampler = new RateSampler(System::nanoTime);
    private final Timer statsTimer;

    ControlFrame(Controller controller, Supplier<Snapshot> latest) {
        super("Điều khiển máy từ xa");
        panel.setPreferredSize(new Dimension(960, 540));
        capture = new InputCapture(controller, panel, panel::hasFocus, panel::requestFocusInWindow);
        panel.addMouseListener(capture);
        panel.addMouseMotionListener(capture);
        panel.addMouseWheelListener(capture);
        panel.addKeyListener(capture);
        panel.addFocusListener(capture);

        stall.setForeground(new Color(0xB71C1C));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bar.add(stats, BorderLayout.WEST);
        bar.add(stall, BorderLayout.EAST);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        add(bar, BorderLayout.SOUTH);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                panel.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                capture.disconnecting(); // release everything on the controlled machine first
                controller.disconnect();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                capture.windowDeactivated();
            }
        });
        pack();
        setLocationByPlatform(true);

        // one timer per second, on the EDT: rates over the real elapsed time
        statsTimer = new Timer(STATS_PERIOD_MS, e -> showRates(sampler.sample(latest.get())));
        statsTimer.start();
    }

    /** EDT: the newest snapshot for the panel and the capture. */
    void apply(Snapshot s) {
        panel.apply(s);
        capture.onApplied(s.generation(), s.inputEpoch());
        stall.setText(s.frame() != null && !s.controlEnabled() ? "Ảnh bị gián đoạn — tạm khóa điều khiển." : " ");
    }

    private void showRates(Rates r) {
        String rtt = r.rttNanos() == TrafficStats.NO_RTT ? "—"
                : String.format(Locale.ROOT, "%.0f ms", r.rttNanos() / 1_000_000.0);
        stats.setText(String.format(Locale.ROOT, "FPS %.1f   Lệnh %.1f KiB/s   Ảnh %.1f KiB/s   RTT %s", r.fps(),
                r.cmdKiBps(), r.imgKiBps(), rtt));
    }

    @Override
    public void dispose() {
        statsTimer.stop();
        super.dispose();
    }
}
