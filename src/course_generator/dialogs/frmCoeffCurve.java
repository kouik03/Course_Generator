/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package course_generator.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import course_generator.CgData;
import course_generator.TrackData;
import course_generator.utils.CgConst;

/**
 * Edit a piecewise-linear coefficient curve using the start, Summary points
 * (marks / stations / time limits) and finish as control points.
 */
public class frmCoeffCurve extends JDialog {

	private static final long serialVersionUID = 1L;

	public static class CoeffCurveResult {
		public boolean Valid;
		public int[] Lines;
		public double[] Coeffs;
	}

	private final DefaultTableModel model;
	private final JTable table;
	private final List<Integer> controlLines = new ArrayList<Integer>();
	private boolean ok;

	public frmCoeffCurve() {
		setTitle("Coefficient curve by aid stations");
		setModal(true);
		setMinimumSize(new Dimension(520, 420));
		setLayout(new BorderLayout(8, 8));

		JLabel help = new JLabel(
				"<html>Edit the coefficient at each control point. Values are interpolated linearly by distance between points.</html>");
		add(help, BorderLayout.NORTH);

		model = new DefaultTableModel(new Object[] { "Point", "Distance (km)", "Coeff (%)" }, 0) {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean isCellEditable(int row, int column) {
				return column == 2;
			}

			@Override
			public Class<?> getColumnClass(int columnIndex) {
				if (columnIndex == 1 || columnIndex == 2)
					return Double.class;
				return String.class;
			}
		};

		table = new JTable(model);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getColumnModel().getColumn(0).setPreferredWidth(180);
		table.getColumnModel().getColumn(1).setPreferredWidth(110);
		table.getColumnModel().getColumn(2).setPreferredWidth(110);
		add(new JScrollPane(table), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> {
			ok = false;
			setVisible(false);
		});
		JButton apply = new JButton("Apply");
		apply.addActionListener(e -> requestClose());
		buttons.add(apply);
		buttons.add(cancel);
		add(buttons, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(null);
	}

	public CoeffCurveResult showDialog(TrackData track) {
		ok = false;
		controlLines.clear();
		model.setRowCount(0);

		if (track == null || track.data == null || track.data.isEmpty()) {
			CoeffCurveResult empty = new CoeffCurveResult();
			empty.Valid = false;
			return empty;
		}

		addControlPoint(track, 0, "Start");

		for (int i = 1; i < track.data.size() - 1; i++) {
			CgData p = track.data.get(i);
			boolean summaryPoint = ((p.getTag() & CgConst.TAG_MARK) != 0) || p.getStation() > 0 || p.getTimeLimit() > 0;
			if (!summaryPoint)
				continue;

			String name = p.getName();
			if (name == null || name.trim().isEmpty())
				name = "Point " + (i + 1);
			addControlPoint(track, i, name);
		}

		int last = track.data.size() - 1;
		if (controlLines.isEmpty() || controlLines.get(controlLines.size() - 1) != last) {
			String name = track.data.get(last).getName();
			if (name == null || name.trim().isEmpty())
				name = "Finish";
			addControlPoint(track, last, name);
		}

		setLocationRelativeTo(null);
		setVisible(true);

		CoeffCurveResult result = new CoeffCurveResult();
		result.Valid = ok;
		if (!ok)
			return result;

		result.Lines = new int[controlLines.size()];
		result.Coeffs = new double[controlLines.size()];
		for (int i = 0; i < controlLines.size(); i++) {
			result.Lines[i] = controlLines.get(i);
			result.Coeffs[i] = ((Number) model.getValueAt(i, 2)).doubleValue();
		}
		return result;
	}

	private void addControlPoint(TrackData track, int line, String name) {
		if (!controlLines.isEmpty() && controlLines.get(controlLines.size() - 1) == line)
			return;

		CgData p = track.data.get(line);
		controlLines.add(line);
		model.addRow(new Object[] {
				name,
				Double.valueOf(Math.round(p.getTotal(CgConst.UNIT_METER) / 10.0) / 100.0),
				Double.valueOf(Math.round(p.getCoeff() * 10.0) / 10.0)
		});
	}

	private void requestClose() {
		if (table.isEditing() && table.getCellEditor() != null)
			table.getCellEditor().stopCellEditing();

		for (int i = 0; i < model.getRowCount(); i++) {
			Object value = model.getValueAt(i, 2);
			double coeff;
			try {
				if (value instanceof Number)
					coeff = ((Number) value).doubleValue();
				else
					coeff = Double.parseDouble(String.valueOf(value).replace(',', '.'));
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "Invalid coefficient on row " + (i + 1));
				return;
			}

			if (Double.isNaN(coeff) || Double.isInfinite(coeff) || coeff < 0.0 || coeff > CgConst.MAX_COEFF) {
				JOptionPane.showMessageDialog(this,
						"Coefficient must be between 0 and " + CgConst.MAX_COEFF + " on row " + (i + 1));
				return;
			}
			model.setValueAt(Double.valueOf(coeff), i, 2);
		}

		ok = true;
		setVisible(false);
	}
}
