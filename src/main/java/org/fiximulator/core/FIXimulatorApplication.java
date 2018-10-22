/*
 * File     : FIXimulatorApplication.java
 *
 * Author   : Zoltan Feledy
 *
 * Contents : This is the application class that contains all the
 *             logic for message handling.
 *
 */

package org.fiximulator.core;

import com.sofi.quotes.Quote;
import com.sofi.quotes.QuoteService;

import quickfix.Application;
import quickfix.DataDictionary;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.UnsupportedMessageType;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecRefID;
import quickfix.field.ExecTransType;
import quickfix.field.ExecType;
import quickfix.field.IDSource;
import quickfix.field.LastPx;
import quickfix.field.LastShares;
import quickfix.field.LeavesQty;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OnBehalfOfSubID;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.SecurityID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.fix42.Message.Header;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Random;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

public class FIXimulatorApplication extends MessageCracker implements Application {

    private boolean connected;
    private JLabel connectedStatus;
    private JLabel executorStatus;
    private boolean executorStarted;
    private Executor executor;
    private Thread executorThread;
    private LogMessageSet messages;
    private SessionSettings settings;
    private SessionID currentSession;
    private DataDictionary dictionary;
    private Random random = new Random();
    private OrderSet orders = null;
    private ExecutionSet executions = null;

    private final ImageIcon greenIcon;
    private final ImageIcon redIcon;

    public FIXimulatorApplication(SessionSettings settings, LogMessageSet messages) {
        this.settings = settings;
        this.messages = messages;
        orders = new OrderSet();
        executions = new ExecutionSet();
        greenIcon = new ImageIcon(getClass().getResource("/org/fiximulator/ui/green.gif"));
        redIcon = new ImageIcon(getClass().getResource("/org/fiximulator/ui/red.gif"));
    }

    public void onCreate(SessionID sessionID) {}

    public void onLogon(SessionID sessionID) {
        connected = true;
        currentSession = sessionID;
        dictionary = Session.lookupSession(currentSession).getDataDictionary();
        if (connectedStatus != null)
            connectedStatus.setIcon(greenIcon);
    }

    public void onLogout(SessionID sessionID) {
        connected = false;
        currentSession = null;
        connectedStatus.setIcon(redIcon);
    }

    // NewOrderSingle handling
    public void onMessage(quickfix.fix42.NewOrderSingle message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        Order order = new Order(message);
        order.setReceivedOrder(true);
        if (executorStarted) {
            orders.add(order, true);
            executorThread.interrupt();
        } else {
            orders.add(order, false);
            boolean autoAck = false;
            try {
                autoAck = settings.getBool("FIXimulatorAutoAcknowledge");
            } catch (Exception e) {}
            if (autoAck) {
                acknowledge(order);
            }
        }
    }

    // OrderCancelRequest handling
    public void onMessage(quickfix.fix42.OrderCancelRequest message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        Order order = new Order(message);
        order.setReceivedCancel(true);
        orders.add(order, false);
        boolean autoPending = false;
        boolean autoCancel = false;
        try {
            autoPending = settings.getBool("FIXimulatorAutoPendingCancel");
            autoCancel = settings.getBool("FIXimulatorAutoCancel");
        } catch (Exception e) {}
        if (autoPending) {
            pendingCancel(order);
        }
        if (autoCancel) {
            cancel(order);
        }
    }

    // OrderReplaceRequest handling
    public void onMessage(quickfix.fix42.OrderCancelReplaceRequest message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        Order order = new Order(message);
        order.setReceivedReplace(true);
        orders.add(order, false);
        boolean autoPending = false;
        boolean autoCancel = false;
        try {
            autoPending = settings.getBool("FIXimulatorAutoPendingReplace");
            autoCancel = settings.getBool("FIXimulatorAutoReplace");
        } catch (Exception e) {}
        if (autoPending) {
            pendingReplace(order);
        }
        if (autoCancel) {
            replace(order);
        }
    }

    // OrderCancelReject handling
    public void onMessage(quickfix.fix42.OrderCancelReject message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {}

    // ExecutionReport handling
    public void onMessage(quickfix.fix42.ExecutionReport message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {}

    public void onMessage(quickfix.fix42.DontKnowTrade message,
            SessionID sessionID)
        throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        try {
            ExecID execID = new ExecID();
            message.get(execID);
            Execution execution =
                    executions.getExecution(execID.getValue().toString());
            execution.setDKd(true);
            executions.update();
        } catch (FieldNotFound ex) {}
    }

    public void fromApp(Message message, SessionID sessionID)
        throws FieldNotFound, IncorrectDataFormat,
            IncorrectTagValue, UnsupportedMessageType {
        messages.add(message, true, dictionary, sessionID);
        crack(message, sessionID);
    }

    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        try {
            messages.add(message, false, dictionary, sessionID);
            crack(message, sessionID);
        } catch (Exception e) {    e.printStackTrace(); }
    }

    public void fromAdmin(Message message, SessionID sessionID)
        throws
        FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {}

    public void toAdmin(Message message, SessionID sessionID) {}

    public void addStatusCallbacks(JLabel connectedStatus, JLabel executorStatus) {
        this.connectedStatus = connectedStatus;
        this.executorStatus = executorStatus;
    }

    public boolean getConnectionStatus() {
        return connected;
    }

    public OrderSet getOrders() {
        return orders;
    }

    public ExecutionSet getExecutions() {
        return executions;
    }

    public SessionSettings getSettings() {
        return settings;
    }

    public void saveSettings() {
        try {
            OutputStream outputStream =
                    new BufferedOutputStream(
                    new FileOutputStream(
                    new File("config/FIXimulator.cfg")));
            settings.toStream(outputStream);
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        }
    }

    // Message handling methods
    public void acknowledge(Order order) {
        Execution acknowledgement = new Execution(order);
        order.setStatus(OrdStatus.NEW);
        acknowledgement.setExecType(ExecType.NEW);
        acknowledgement.setExecTranType(ExecTransType.NEW);
        acknowledgement.setLeavesQty(order.getOpen());
        sendExecution(acknowledgement);
        order.setReceivedOrder(false);
        orders.update();
    }

    public void reject(Order order) {
        Execution reject = new Execution(order);
        order.setStatus(OrdStatus.REJECTED);
        reject.setExecType(ExecType.REJECTED);
        reject.setExecTranType(ExecTransType.NEW);
        reject.setLeavesQty(order.getOpen());
        sendExecution(reject);
        order.setReceivedOrder(false);
        orders.update();
    }

    public void dfd(Order order) {
        Execution dfd = new Execution(order);
        order.setStatus(OrdStatus.DONE_FOR_DAY);
        dfd.setExecType(ExecType.DONE_FOR_DAY);
        dfd.setExecTranType(ExecTransType.NEW);
        dfd.setLeavesQty(order.getOpen());
        dfd.setCumQty(order.getExecuted());
        dfd.setAvgPx(order.getAvgPx());
        sendExecution(dfd);
        orders.update();
    }

    public void pendingCancel(Order order) {
        Execution pending = new Execution(order);
        order.setStatus(OrdStatus.PENDING_CANCEL);
        pending.setExecType(ExecType.PENDING_CANCEL);
        pending.setExecTranType(ExecTransType.NEW);
        pending.setLeavesQty(order.getOpen());
        pending.setCumQty(order.getExecuted());
        pending.setAvgPx(order.getAvgPx());
        sendExecution(pending);
        order.setReceivedCancel(false);
        orders.update();
    }

    public void cancel(Order order) {
        Execution cancel = new Execution(order);
        order.setStatus(OrdStatus.CANCELED);
        cancel.setExecType(ExecType.CANCELED);
        cancel.setExecTranType(ExecTransType.NEW);
        cancel.setLeavesQty(order.getOpen());
        cancel.setCumQty(order.getExecuted());
        cancel.setAvgPx(order.getAvgPx());
        sendExecution(cancel);
        order.setReceivedCancel(false);
        orders.update();
    }

    public void rejectCancelReplace(Order order, boolean cancel) {
        order.setReceivedCancel(false);
        order.setReceivedReplace(false);
        // *** Required fields ***
        // OrderID (37)
        OrderID orderID = new OrderID(order.getID());

        ClOrdID clientID = new ClOrdID(order.getClientID());

        OrigClOrdID origClientID = new OrigClOrdID(order.getOrigClientID());

        // OrdStatus (39) Status as a result of this report
        if (order.getStatus().equals("<UNKNOWN>"))
            order.setStatus(OrdStatus.NEW);
        OrdStatus ordStatus = new OrdStatus(order.getFIXStatus());

        CxlRejResponseTo responseTo = new CxlRejResponseTo();
        if (cancel) {
            responseTo.setValue(CxlRejResponseTo.ORDER_CANCEL_REQUEST);
        } else {
            responseTo.setValue(CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
        }

        // Construct OrderCancelReject message from required fields
        quickfix.fix42.OrderCancelReject rejectMessage =
                 new quickfix.fix42.OrderCancelReject(
                    orderID,
                    clientID,
                    origClientID,
                    ordStatus,
                    responseTo);

        // *** Send message ***
        sendMessage(rejectMessage);
        orders.update();
    }

    public void pendingReplace(Order order) {
        Execution pending = new Execution(order);
        order.setStatus(OrdStatus.PENDING_REPLACE);
        pending.setExecType(ExecType.PENDING_REPLACE);
        pending.setExecTranType(ExecTransType.NEW);
        pending.setLeavesQty(order.getOpen());
        pending.setCumQty(order.getExecuted());
        pending.setAvgPx(order.getAvgPx());
        order.setReceivedReplace(false);
        sendExecution(pending);
        orders.update();
    }

    public void replace(Order order) {
        Execution replace = new Execution(order);
        order.setStatus(OrdStatus.REPLACED);
        replace.setExecType(ExecType.REPLACE);
        replace.setExecTranType(ExecTransType.NEW);
        replace.setLeavesQty(order.getOpen());
        replace.setCumQty(order.getExecuted());
        replace.setAvgPx(order.getAvgPx());
        order.setReceivedReplace(false);
        sendExecution(replace);
        orders.update();
    }

    public void execute(Execution execution) {
        Order order = execution.getOrder();
        double fillQty = execution.getLastShares();
        double fillPrice = execution.getLastPx();
        double open = order.getOpen();
        // partial fill
        if (fillQty < open) {
            order.setOpen(open - fillQty);
            order.setStatus(OrdStatus.PARTIALLY_FILLED);
            execution.setExecType(ExecType.PARTIAL_FILL);
            // full or over execution
        } else {
            order.setOpen(0);
            order.setStatus(OrdStatus.FILLED);
            execution.setExecType(ExecType.FILL);
        }
        double avgPx = (order.getAvgPx() * order.getExecuted()
                     + fillPrice * fillQty)
                     / (order.getExecuted() + fillQty);
        order.setAvgPx(avgPx);
        order.setExecuted(order.getExecuted() + fillQty);
        orders.update();
        // update execution
        execution.setExecTranType(ExecTransType.NEW);
        execution.setLeavesQty(order.getOpen());
        execution.setCumQty(order.getExecuted());
        execution.setAvgPx(avgPx);
        sendExecution(execution);
    }

    public void bust(Execution execution) {
        Execution bust = execution.clone();
        Order order = execution.getOrder();
        double fillQty = execution.getLastShares();
        double fillPrice = execution.getLastPx();
        double executed = order.getExecuted();
        // partial fill
        if (fillQty < executed) {
            order.setOpen(executed - fillQty);
            order.setStatus(OrdStatus.PARTIALLY_FILLED);
            double avgPx = (order.getAvgPx() * executed
                         - fillPrice * fillQty)
                         / (order.getExecuted() - fillQty);
            order.setAvgPx(avgPx);
            order.setExecuted(order.getExecuted() - fillQty);
            // full or over execution
        } else {
            order.setOpen(order.getQuantity());
            order.setStatus(OrdStatus.NEW);
            order.setAvgPx(0);
            order.setExecuted(0);
        }
        orders.update();
        // update execution
        bust.setExecTranType(ExecTransType.CANCEL);
        bust.setLeavesQty(order.getOpen());
        bust.setCumQty(order.getExecuted());
        bust.setAvgPx(order.getAvgPx());
        sendExecution(bust);
    }

    public void correct(Execution correction) {
        Order order = correction.getOrder();
        Execution original = executions.getExecution(correction.getRefID());

        double fillQty = correction.getLastShares();
        double oldQty = original.getLastShares();

        double fillPrice = correction.getLastPx();
        double oldPrice = original.getLastPx();

        double executed = order.getExecuted();
        double ordered = order.getQuantity();

        double newCumQty = executed - oldQty + fillQty;
        double avgPx = (order.getAvgPx() * executed
                     - oldPrice * oldQty
                     + fillPrice * fillQty)
                     / newCumQty;

        // partial fill
        if (newCumQty < ordered) {
            order.setOpen(ordered - newCumQty);
            order.setStatus(OrdStatus.PARTIALLY_FILLED);
        // full or over execution
        } else {
            order.setOpen(0);
            order.setStatus(OrdStatus.FILLED);
        }

        order.setAvgPx(avgPx);
        order.setExecuted(newCumQty);
        orders.update();

        // update execution
        correction.setExecTranType(ExecTransType.CORRECT);
        correction.setLeavesQty(order.getOpen());
        correction.setCumQty(order.getExecuted());
        correction.setAvgPx(order.getAvgPx());
        sendExecution(correction);
    }

    // Message sending methods
    public void sendMessage(Message message) {
        String oboCompID = "<UNKNOWN>";
        String oboSubID = "<UNKNOWN>";
        boolean sendoboCompID = false;
        boolean sendoboSubID = false;

        try {
            oboCompID = settings.getString(currentSession, "OnBehalfOfCompID");
            oboSubID = settings.getString(currentSession, "OnBehalfOfSubID");
            sendoboCompID = settings.getBool("FIXimulatorSendOnBehalfOfCompID");
            sendoboSubID = settings.getBool("FIXimulatorSendOnBehalfOfSubID");
        } catch (Exception e) {}

        // Add OnBehalfOfCompID
        if (sendoboCompID && !oboCompID.equals("")) {
            OnBehalfOfCompID onBehalfOfCompID = new OnBehalfOfCompID(oboCompID);
            Header header = (Header) message.getHeader();
            header.set(onBehalfOfCompID);
        }

        // Add OnBehalfOfSubID
        if (sendoboSubID && !oboSubID.equals("")) {
            OnBehalfOfSubID onBehalfOfSubID = new OnBehalfOfSubID(oboSubID);
            Header header = (Header) message.getHeader();
            header.set(onBehalfOfSubID);
        }

        // Send actual message
        try {
            Session.sendToTarget(message, currentSession);
        } catch (SessionNotFound e) { e.printStackTrace(); }
    }

    public void sendExecution(Execution execution) {
        Order order = execution.getOrder();

        // *** Required fields ***
        // OrderID (37)
        OrderID orderID = new OrderID(order.getID());

        // ExecID (17)
        ExecID execID = new ExecID(execution.getID());

        // ExecTransType (20)
        ExecTransType execTransType =
                new ExecTransType(execution.getFIXExecTranType());

        // ExecType (150) Status of this report
        ExecType execType = new ExecType(execution.getFIXExecType());

        // OrdStatus (39) Status as a result of this report
        OrdStatus ordStatus =
                new OrdStatus(execution.getOrder().getFIXStatus());

        // Symbol (55)
        Symbol symbol = new Symbol(execution.getOrder().getSymbol());

        //  Side (54)
        Side side = new Side(execution.getOrder().getFIXSide());

        // LeavesQty ()
        LeavesQty leavesQty = new LeavesQty(execution.getLeavesQty());

        // CumQty ()
        CumQty cumQty = new CumQty(execution.getCumQty());

        // AvgPx ()
        AvgPx avgPx = new AvgPx(execution.getAvgPx());

        // Construct Execution Report from required fields
        quickfix.fix42.ExecutionReport executionReport =
                new quickfix.fix42.ExecutionReport(
                    orderID,
                    execID,
                    execTransType,
                    execType,
                    ordStatus,
                    symbol,
                    side,
                    leavesQty,
                    cumQty,
                    avgPx);

        // *** Conditional fields ***
        if (execution.getRefID() != null) {
            executionReport.set(
                    new ExecRefID(execution.getRefID()));
        }

        // *** Optional fields ***
        executionReport.set(new ClOrdID(execution.getOrder().getClientID()));
        executionReport.set(new OrderQty(execution.getOrder().getQuantity()));
        executionReport.set(new LastShares(execution.getLastShares()));
        executionReport.set(new LastPx(execution.getLastPx()));
        System.out.println("Setting...");
        System.out.println("SecurityID: " + order.getSecurityID());
        System.out.println("IDSource: " + order.getIdSource());
        if (order.getSecurityID() != null
            && order.getIdSource() != null) {
            executionReport.set(new SecurityID(order.getSecurityID()));
            executionReport.set(new IDSource(order.getIdSource()));
        }

        // *** Send message ***
        sendMessage(executionReport);
        executions.add(execution);
    }

    // Executor methods
    public void startExecutor(Integer delay, Integer partials, QuoteService quoteService) {
        try {
            executor = new Executor(delay, partials, quoteService);
            executorThread = new Thread(executor);
            executorThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopExecutor() {
        executor.stopExecutor();
        executorThread.interrupt();
        try {
            executorThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void setNewExecutorDelay(Integer delay) {
        if (executorStarted) {
            executor.setDelay(delay);
        }
    }

    public void setNewExecutorPartials(Integer partials) {
        if (executorStarted) {
            executor.setPartials(partials);
        }
    }

    public class Executor implements Runnable {
        private Integer delay;
        private Integer partials;
        private QuoteService quoteService;

        public Executor(Integer delay, Integer partials, QuoteService quoteService) {
            executorStarted = true;
            this.partials = partials;
            this.delay = delay;
            this.quoteService = quoteService;
        }

        public void run() {
            executorStatus.setIcon(greenIcon);
            while (executorStarted) {
                if (connected) {
                    while (orders.haveOrdersToFill()) {
                        Order order = orders.getOrderToFill();
                        acknowledge(order);
                        fill(order);
                    }
                }
                // No orders to fill, check again in 5 seconds
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                }
            }
            executorStatus.setIcon(redIcon);
        }

        public void stopExecutor() {
            executorStarted = false;
        }

        public void setDelay(Integer delay) {
            this.delay = delay;
        }

        public void setPartials(Integer partials) {
            this.partials = partials;
        }

        public void fill(Order order) {
            int pricePrecision = getSettingLong("FIXimulatorPricePrecision", 4);

            double fillQty = Math.floor(order.getQuantity() / partials);
            double fillPrice;
            try {
                Quote quote = quoteService.getQuote(order.getSymbol());
                fillPrice = quote.getLast().getValue().doubleValue();
            } catch (Exception ignored) {
                // Random price
                final double factor = Math.pow(10, pricePrecision);
                fillPrice = Math.round(random.nextDouble() * 100 * factor) / factor;
            }

            char ordStatus;
            char execType;

            if (fillQty == 0) {
                fillQty = 1;
            }
            for (int i = 0; i < partials; i++) {
                double open = order.getOpen();
                if (open > 0) {
                    double priorQty = order.getExecuted();
                    double priorAvg = order.getAvgPx();
                    double thisAvg = ((fillQty * fillPrice)
                                      + (priorQty * priorAvg))
                                     / (priorQty + fillQty);

                    double factor = Math.pow(10, pricePrecision);
                    thisAvg = Math.round(thisAvg * factor) / factor;

                    if (fillQty < open && i != partials - 1) {
                        // send partial
                        ordStatus = OrdStatus.PARTIALLY_FILLED;
                        execType = ExecType.PARTIAL_FILL;
                    } else {
                        // send full
                        fillQty = open;
                        ordStatus = OrdStatus.FILLED;
                        execType = ExecType.FILL;
                    }

                    // update order
                    updateOrder(order, open - fillQty, order.getExecuted() + fillQty, thisAvg, ordStatus);

                    // create execution
                    Execution execution = createExecution(order, execType, thisAvg, fillQty, fillPrice);

                    sendExecution(execution);
                }
                try {
                    Thread.sleep(delay.longValue());
                } catch (InterruptedException e) {
                }
            }
        }

        private Execution createExecution(Order order, char execType, double thisAvg, double fillQty, double fillPrice) {
            Execution execution = new Execution(order);
            execution.setExecType(execType);
            execution.setExecTranType(ExecTransType.NEW);
            execution.setLeavesQty(order.getOpen());
            execution.setCumQty(order.getQuantity() - order.getOpen());
            execution.setAvgPx(thisAvg);
            execution.setLastShares(fillQty);
            execution.setLastPx(fillPrice);
            return execution;
        }

        private void updateOrder(Order order, double open, double executed, double thisAvg, char status) {
            order.setOpen(open);
            order.setExecuted(executed);
            order.setAvgPx(thisAvg);
            order.setStatus(status);
            orders.update();
        }

        private int getSettingLong(String key, int defaultValue) {
            try {
                return (int)settings.getLong(key);
            } catch (Exception e) {
            }
            return defaultValue;
        }
    }
}
