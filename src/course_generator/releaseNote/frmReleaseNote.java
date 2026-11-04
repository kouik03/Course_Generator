/*
 * Course Generator
 * Copyright (C) 2008-2019 Pierre Delore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package course_generator.releaseNote;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JOptionPane;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import course_generator.utils.CgConst;
import javax.swing.KeyStroke;

import course_generator.settings.CgSettings;
import course_generator.utils.CgLog;
import course_generator.utils.Utils;

public class frmReleaseNote extends javax.swing.JDialog {
	private static final long serialVersionUID = -5710076240042256421L;
	private ResourceBundle bundle;
	private CgSettings settings;
	private String version;
	private JEditorPane editorStat;
	private JScrollPane scrollPaneStat;
	private JPanel jPanelButtons;
	private JButton btClose;
	private JCheckBox chkDisable;

	/**
	 * Creates new form frmSettings
	 */

	public frmReleaseNote(java.awt.Frame parent, CgSettings settings, String version) {
		super(parent, true);
		this.settings = settings;
		this.version = version;
		bundle = java.util.ResourceBundle.getBundle("course_generator/Bundle");
		initComponents();
		// setModal(true);
	}

	public void showDialog(CgSettings _settings) {
		settings = _settings;

		// Set field
		refresh();

		// -- Update the display
		// Refresh();

		// -- Show the dialog
		setVisible(true);
	}

	/**
	 * Manage low level key strokes ESCAPE : Close the window
	 *
	 * @return
	 */
	protected JRootPane createRootPane() {
		JRootPane rootPane = new JRootPane();
		KeyStroke strokeEscape = KeyStroke.getKeyStroke("ESCAPE");

		@SuppressWarnings("serial")
		Action actionListener = new AbstractAction() {
			public void actionPerformed(ActionEvent actionEvent) {
				closeDialog();
			}
		};

		InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		inputMap.put(strokeEscape, "ESCAPE");
		rootPane.getActionMap().put("ESCAPE", actionListener);

		return rootPane;
	}

	private void initComponents() {

		// Every close path must persist the checkbox, not only the Close button.
		setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				closeDialog();
			}
		});
		setTitle(bundle.getString("frmReleaseNote.title")); // Release notes
		setAlwaysOnTop(true);

		// -- Layout
		// ------------------------------------------------------------
		Container paneGlobal = getContentPane();
		paneGlobal.setLayout(new GridBagLayout());

		editorStat = new JEditorPane();
		editorStat.setContentType("text/html");
		editorStat.setEditable(false);
		editorStat.setPreferredSize(new Dimension(740, 480));
		scrollPaneStat = new JScrollPane(editorStat);

		Utils.addComponent(paneGlobal, scrollPaneStat, 0, 0, 1, 1, 1, 1, 10, 10, 0, 10,
				GridBagConstraints.BASELINE_LEADING, GridBagConstraints.BOTH);

		chkDisable = new javax.swing.JCheckBox();
		chkDisable.setText(bundle.getString("frmReleaseNote.chkDisable.Text")); // Stop displaying this dialog box.
		Utils.addComponent(paneGlobal, chkDisable, 0, 1, 1, 1, 1, 0, 10, 10, 10, 10,
				GridBagConstraints.BASELINE_LEADING, GridBagConstraints.BOTH);

		// == BUTTONS
		// ===========================================================
		jPanelButtons = new javax.swing.JPanel();
		jPanelButtons.setLayout(new FlowLayout());
		Utils.addComponent(paneGlobal, jPanelButtons, 0, 2, GridBagConstraints.REMAINDER, 1, 0, 0, 0, 0, 0, 0,
				GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL);

		btClose = new javax.swing.JButton();
		btClose.setText(bundle.getString("frmReleaseNote.btClose.text")); // Close
		btClose.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				closeDialog();
			}
		});

		// -- Add buttons
		jPanelButtons.add(btClose);

		// --
		pack();
		setLocationRelativeTo(null);
	}

	/**
	 * Persist the preference immediately, even if Maven later stops the process
	 * without triggering frmMain.formWindowClosing/SaveConfig.
	 */
	private void closeDialog() {
		try {
			ReleaseNotePreference.saveOnClose(settings, version, chkDisable.isSelected(),
					Utils.GetHomeDir() + "/" + CgConst.CG_DIR);
		} catch (RuntimeException ex) {
			CgLog.error("Release notes preference could not be saved: " + ex.getMessage());
			JOptionPane.showMessageDialog(this, ex.getMessage(),
					bundle.getString("frmReleaseNote.title"), JOptionPane.WARNING_MESSAGE);
		}
		dispose();
	}

	public void refresh() {
		chkDisable.setSelected(!ReleaseNotePreference.shouldShowAtStartup(settings, version));
		StringBuilder sb = new StringBuilder();

		// -- Get current language
		String lang = Locale.getDefault().toString();

		InputStream is = getClass().getResourceAsStream("releaseNote_" + lang + ".html");
		// -- File exist?
		if (is == null) {
			/// -- Use default file
			is = getClass().getResourceAsStream("releaseNote_en_US.html");
			CgLog.info("RefreshStat: Release note file not present! Loading the english release note file");
		}

		try {
			InputStreamReader isr = new InputStreamReader(is, "UTF-8");
			BufferedReader br = new BufferedReader(isr);

			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			br.close();
			isr.close();
			is.close();
		} catch (IOException e) {
			CgLog.error("frmReleaseNote : Impossible to read the release note file from resource");
			e.printStackTrace();
		}

		// -- Refresh the view and set the cursor position
		editorStat.setText(sb.toString());
		editorStat.setCaretPosition(0);
	}

}
