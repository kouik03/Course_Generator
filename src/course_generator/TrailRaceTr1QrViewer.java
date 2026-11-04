/*
 * Large, offline QR display for scanning TrailRace+ PRO TR1 data from a phone.
 * QR payloads are the EXACT original parts; the block number is only a label.
 */
package course_generator;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

public final class TrailRaceTr1QrViewer extends JDialog {
    private static final long serialVersionUID = 1L;

    private final BufferedImage[] images;
    private final String[] parts;
    private final JLabel caption = new JLabel("", SwingConstants.CENTER);
    private final JLabel imageView = new JLabel("", SwingConstants.CENTER);
    private final JButton previous = new JButton("◀ Précédent");
    private final JButton next = new JButton("Suivant ▶");
    private final JButton zoom = new JButton("Taille réelle");
    private boolean actualSize;
    private int index;
    private final int previewSide;

    public TrailRaceTr1QrViewer(Dialog owner, BufferedImage[] images, String[] parts, int initialIndex) {
        super(owner, "QR TrailRace+ PRO", false);
        if (images == null || parts == null || images.length == 0 || images.length != parts.length)
            throw new IllegalArgumentException("Images QR et blocs TR1 incohérents.");
        this.images = images;
        this.parts = parts;
        this.index = Math.max(0, Math.min(images.length - 1, initialIndex));
        Dimension screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds().getSize();
        this.previewSide = Math.max(300, Math.min(900, Math.min(screen.width - 140, screen.height - 220)));

        setLayout(new BorderLayout(8, 8));
        JPanel north = new JPanel(new BorderLayout());
        caption.setFont(caption.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        north.add(caption, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(imageView), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton save = new JButton("Enregistrer PNG");
        JButton close = new JButton("Fermer");
        actions.add(previous);
        actions.add(next);
        actions.add(zoom);
        actions.add(save);
        actions.add(close);
        add(actions, BorderLayout.SOUTH);

        previous.addActionListener(e -> showBlock(index - 1));
        next.addActionListener(e -> showBlock(index + 1));
        zoom.addActionListener(e -> { actualSize = !actualSize; showBlock(index); });
        save.addActionListener(e -> saveCurrent());
        close.addActionListener(e -> dispose());

        setSize(Math.min(screen.width - 48, previewSide + 80),
                Math.min(screen.height - 70, previewSide + 130));
        setLocationRelativeTo(owner);
        showBlock(this.index);
    }

    private void showBlock(int requested) {
        if (requested < 0 || requested >= images.length) return;
        this.index = requested;
        caption.setText(String.format("Bloc %d/%d — %d caractères — scanner ce QR dans TrailRace+ PRO",
                index + 1, images.length, parts[index].length()));
        previous.setEnabled(index > 0);
        next.setEnabled(index < images.length - 1);
        zoom.setText(actualSize ? "Adapter à la fenêtre" : "Taille réelle (1200 px)");
        BufferedImage image = images[index];
        if (actualSize || image.getWidth() <= previewSide) {
            imageView.setIcon(new ImageIcon(image));
        } else {
            // Nearest-neighbor avoids blur on the high-density QR modules.
            BufferedImage scaled = new BufferedImage(previewSide, previewSide, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, 0, 0, previewSide, previewSide, null);
            } finally { g.dispose(); }
            imageView.setIcon(new ImageIcon(scaled));
        }
    }

    private void saveCurrent() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(String.format("TrailRace_TR1_bloc_%d.png", index + 1)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.exists() && JOptionPane.showConfirmDialog(this,
                "Remplacer " + file.getName() + " ?", "Confirmation",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            TrailRaceTr1Qr.savePng(images[index], file);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Enregistrement PNG",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
