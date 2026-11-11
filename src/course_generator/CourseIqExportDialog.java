package course_generator;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** First offline CGIQ export UI. No HTTP, no TrailRace TR1 format. */
public final class CourseIqExportDialog {
    private CourseIqExportDialog() {}
    public static void showDialog(java.awt.Frame owner, TrackData track) {
        try {
            CourseIqEncoder.Result result=CourseIqEncoder.encode(track);
            JDialog dialog=new JDialog(owner,"Exporter Course IQ (3 blocs)",true);
            dialog.setLayout(new BorderLayout(8,8));
            dialog.add(new JLabel("Format CGIQ v1 — "+result.routePoints+
                " points de trace, "+result.checkpoints+" passages. Chaque bloc ≤ 2000 caractères."),
                BorderLayout.NORTH);
            JPanel fields=new JPanel(new GridLayout(3,1,4,8));
            for(int i=0;i<3;i++) {
                final String value=result.blocks[i];
                JPanel section=new JPanel(new BorderLayout());
                JTextArea area=new JTextArea(value,4,55);
                area.setLineWrap(true); area.setWrapStyleWord(false);
                area.setEditable(false);
                JButton copy=new JButton("Copier bloc "+(i+1)+" ("+value.length()+" caractères)");
                copy.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value),null));
                section.add(copy,BorderLayout.NORTH);
                section.add(new JScrollPane(area),BorderLayout.CENTER);
                fields.add(section);
            }
            dialog.add(fields,BorderLayout.CENTER);
            JPanel actions=new JPanel();
            JButton save=new JButton("Enregistrer les 3 blocs en .txt");
            save.addActionListener(e->{
                JFileChooser chooser=new JFileChooser();
                chooser.setSelectedFile(new File("course-iq-blobs.txt"));
                if(chooser.showSaveDialog(dialog)==JFileChooser.APPROVE_OPTION) {
                    try {
                        Files.write(chooser.getSelectedFile().toPath(),
                            (result.blocks[0]+"\n"+result.blocks[1]+"\n"+result.blocks[2]+"\n")
                            .getBytes(StandardCharsets.UTF_8));
                    } catch(Exception ex) {
                        JOptionPane.showMessageDialog(dialog,ex.getMessage(),"Erreur",JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
            JButton close=new JButton("Fermer");
            close.addActionListener(e->dialog.dispose());
            actions.add(save);actions.add(close);
            dialog.add(actions,BorderLayout.SOUTH);
            dialog.pack();dialog.setLocationRelativeTo(owner);dialog.setVisible(true);
        } catch(Exception ex) {
            JOptionPane.showMessageDialog(owner,ex.getMessage(),"Export Course IQ",JOptionPane.ERROR_MESSAGE);
        }
    }
}
