/*
 * File     : MessageTableModel.java
 *
 * Author   : Zoltan Feledy
 *
 * Contents : This class is the TableModel for the Message Table.
 *
 */

package org.fiximulator.ui;

import org.fiximulator.core.FIXimulator;
import org.fiximulator.core.LogMessage;
import org.fiximulator.core.LogMessageSet;
import quickfix.field.converter.UtcTimestampConverter;

import javax.swing.table.AbstractTableModel;

public class MessageTableModel extends AbstractTableModel {
    private static LogMessageSet messages = FIXimulator.getMessageSet();
    private static String[] columns =
        {"#", "Direction", "SendingTime", "Type", "Message"};

    public MessageTableModel() {
        messages.addCallback(this);
    }

    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Class getColumnClass(int column) {
        if (column == 0) return Integer.class;
        return String.class;
    }

    public int getRowCount() {
        return messages.getCount();
    }

    public Object getValueAt(int row, int column) {
        LogMessage msg = messages.getMessage(row);
        if (column == 0) return msg.getMessageIndex();
        if (column == 1) return (msg.isIncoming() ? "incoming" : "outgoing");
        if (column == 2) return UtcTimestampConverter.convert(msg.getSendingTime(), true);
        if (column == 3) return msg.getMessageTypeName();
        if (column == 4) return msg.getRawMessage();
        return new Object();
    }

    public void update() {
        fireTableDataChanged();
    }
}
