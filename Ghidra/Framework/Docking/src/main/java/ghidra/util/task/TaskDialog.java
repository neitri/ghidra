/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.util.task;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

import docking.DialogComponentProvider;
import docking.DockingWindowManager;
import docking.widgets.OptionDialog;
import ghidra.util.HelpLocation;
import ghidra.util.SystemUtilities;
import ghidra.util.exception.CancelledException;
import ghidra.util.timer.GTimer;

/**
 * Dialog that is displayed to show activity for a Task that is running outside of the 
 * Swing Thread.
 */
public class TaskDialog extends DialogComponentProvider implements TaskMonitor {

	/** Timer used to give the task a chance to complete */
	private static final int SLEEPY_TIME = 10;
	
	/** Amount of time to wait before showing the monitor dialog */
	private final static int MAX_DELAY = 200000;
	
	public final static int DEFAULT_WIDTH = 275;

	private Timer showTimer;
	private AtomicInteger taskID = new AtomicInteger();
	private Runnable closeDialog;
	private Component centerOnComp;
	private Runnable shouldCancelRunnable;
	private boolean taskDone;
	private JPanel mainPanel;
	private ChompingBitsAnimationPanel chompingBitsPanel;
	private TaskMonitorComponent monitorComponent;

	/** Runnable that updates the primary message label in the dialog */
	private Runnable updatePrimaryMessageRunnable;

	/** Runnable that updates the secondary message label in the dialog */
	private Runnable updateSecondaryMessageRunnable;

	/** If not null, then the value of the string has yet to be rendered */
	private String newPrimaryMessage;

	/** If not null, then the value of the string has yet to be rendered */
	private String newSecondaryMessage;

	/** 
	 * Indicates if this monitor has been initialized for progress updates. If this value
	 * is set to true, the {@link TaskMonitorService} will not return the monitor to 
	 * another caller (only one client should be able to update progress at a time).
	 */
	private AtomicBoolean initialized = new AtomicBoolean(false);

	private SecondaryTaskMonitor secondaryTaskMonitor;

	/** 
	 * Constructor
	 * 
	 * @param centerOnComp component to be centered over when shown,
	 * otherwise center over parent.  If both centerOnComp and parent
	 * are null, dialog will be centered on screen.
	 * @param task the Task that this dialog will be associated with
	 */
	public TaskDialog(Component centerOnComp, Task task) {
		this(centerOnComp, task.getTaskTitle(), task.isModal(), task.canCancel(),
			task.hasProgress());
	}

	/**
	 * Constructor
	 *  
	 * @param task the Task that this dialog will be associated with
	 */
	public TaskDialog(Task task) {
		this(task.getTaskTitle(), task.canCancel(), task.isModal(), task.hasProgress());
	}

	/**
	 * Constructor
	 * 
	 * @param title title for the dialog
	 * @param canCancel true if the task can be canceled
	 * @param isModal true if the dialog should be modal
	 * @param hasProgress true if the dialog should show a progress bar
	 */
	public TaskDialog(String title, boolean canCancel, boolean isModal, boolean hasProgress) {
		this(null, title, isModal, canCancel, hasProgress);
	}
	
	/**
	 * Constructor
	 * 
	 * @param centerOnComp component to be centered over when shown, otherwise center over 
	 *        parent.  If both centerOnComp is null, then the active window will be used
	 * @param title title for the dialog
	 * @param isModal true if the dialog should be modal
	 * @param canCancel true if the task can be canceled
	 * @param hasProgress true if the dialog should show a progress bar
	 */
	private TaskDialog(Component centerOnComp, String title, boolean isModal, boolean canCancel,
			boolean hasProgress) {
		super(title, isModal, true, canCancel, true);
		this.centerOnComp = centerOnComp;
		setup(canCancel, hasProgress);
	}

	@Override
	public boolean isInitialized() {
		return initialized.get();
	}

	@Override
	public void setInitialized(boolean init) {
		this.initialized.set(init);
	}

	private void setup(boolean canCancel, boolean hasProgress) {
		monitorComponent = new TaskMonitorComponent(false, false);
		chompingBitsPanel = new ChompingBitsAnimationPanel();
		
		setCancelEnabled(canCancel);
		setRememberLocation(false);
		setRememberSize(false);
		setTransient(true);
		closeDialog = () -> {
			close();
			dispose();
		};
		updatePrimaryMessageRunnable = () -> {
			setStatusText(newPrimaryMessage);
			synchronized (TaskDialog.this) {
				newPrimaryMessage = null;
			}
		};
		updateSecondaryMessageRunnable = () -> {
			setSubStatusText(newSecondaryMessage);
			synchronized (TaskDialog.this) {
				newSecondaryMessage = null;
			}
		};
		shouldCancelRunnable = () -> {
			int currentTaskID = taskID.get();

			boolean doCancel = promptToVerifyCancel();
			if (doCancel && currentTaskID == taskID.get()) {
				cancel();
			}
		};

		mainPanel = new JPanel(new BorderLayout());
		addWorkPanel(mainPanel);
		
		if (hasProgress) {
			installProgressMonitor();
		}
		else {
			installActivityDisplay();
		}

		if (canCancel) {
			addCancelButton();
		}

		// SPLIT the help for this dialog should not be in the front end plugin.
		setHelpLocation(new HelpLocation("Tool", "TaskDialog"));
	}

	/**
	 * Shows a dialog asking the user if they really, really want to cancel the task
	 * 
	 * @return true if the task should be cancelled
	 */
	protected boolean promptToVerifyCancel() {
		boolean userSaysYes = OptionDialog.showYesNoDialog(getComponent(), "Cancel?",
			"Do you really want to cancel \"" + getTitle() + "\"?") == OptionDialog.OPTION_ONE;

		return userSaysYes;
	}

	/**
	 * Adds the panel that contains the progress bar to the dialog
	 */
	private void installProgressMonitor() {
		SystemUtilities.runIfSwingOrPostSwingLater(() -> {
			mainPanel.removeAll();
			mainPanel.add(monitorComponent, BorderLayout.CENTER);
			repack();
		});
	}

	/**
	 * Adds the panel that contains the chomping bits animation to the dialog. This should only be 
	 * called if the dialog has no need to display progress.
	 */
	private void installActivityDisplay() {
		SystemUtilities.runIfSwingOrPostSwingLater(() -> {
			mainPanel.removeAll();
			mainPanel.add(chompingBitsPanel, BorderLayout.CENTER);
			repack();
		});
	}

	@Override
	protected void dialogShown() {
		// our task may have completed while we were queued up to be shown
		if (isCompleted()) {
			close();
		}
	}

	@Override
	protected void dialogClosed() {
		close();
	}

	@Override
	public void setShowProgressValue(boolean showProgressValue) {
		monitorComponent.setShowProgressValue(showProgressValue);
	}

	/** 
	 * Sets the percentage done
	 * 
	 * @param param The percentage of the task completed.
	 */
	@Override
	public void setProgress(long param) {
		monitorComponent.setProgress(param);
	}

	@Override
	public void initialize(long max) {
		if (monitorComponent.isIndeterminate()) {
			// don't show the progress bar if we have already been marked as indeterminate (this
			// allows us to prevent low-level algorithms from changing the display settings).
			return;
		}

		if (!monitorComponent.isShowing()) {
			installProgressMonitor();
		}

		monitorComponent.initialize(max);
	}

	@Override
	public void setMaximum(long max) {
		monitorComponent.setMaximum(max);
	}

	@Override
	public long getMaximum() {
		return monitorComponent.getMaximum();
	}

	/**
	 * Sets the <code>indeterminate</code> property of the progress bar,
	 * which determines whether the progress bar is in determinate
	 * or indeterminate mode.
	 * An indeterminate progress bar continuously displays animation
	 * indicating that an operation of unknown length is occurring.
	 * By default, this property is <code>false</code>.
	 * Some look and feels might not support indeterminate progress bars;
	 * they will ignore this property.
	 *
	 * @see JProgressBar
	 */
	@Override
	public void setIndeterminate(final boolean indeterminate) {
		monitorComponent.setIndeterminate(indeterminate);
	}

	@Override
	protected void cancelCallback() {
		SwingUtilities.invokeLater(shouldCancelRunnable);
	}

	@Override
	synchronized public void setMessage(String str) {
		boolean invoke = (newPrimaryMessage == null);
		if (invoke) {
			newPrimaryMessage = str;
			SwingUtilities.invokeLater(updatePrimaryMessageRunnable);
		}
	}

	/**
	 * Updates the secondary message on the task monitor
	 * 
	 * @param str the string to update
	 */
	synchronized public void setSecondaryMessage(String str) {
		boolean invoke = (newSecondaryMessage == null);
		if (invoke) {
			newSecondaryMessage = str;
			SwingUtilities.invokeLater(updateSecondaryMessageRunnable);
		}
	}

	@Override
	public void setCancelEnabled(boolean enable) {
		monitorComponent.setCancelEnabled(enable);
		super.setCancelEnabled(enable);
	}

	@Override
	public boolean isCancelEnabled() {
		return super.isCancelEnabled();
	}

	public synchronized void taskProcessed() {
		taskDone = true;
		monitorComponent.notifyChangeListeners();
		SwingUtilities.invokeLater(closeDialog);
	}

	public synchronized void reset() {
		taskDone = false;
		taskID.incrementAndGet();
	}

	public synchronized boolean isCompleted() {
		return taskDone;
	}

	@Override
	public boolean isCancelled() {
		return monitorComponent.isCancelled();
	}

	/**
	 * Shows the dialog window centered on the parent window.
	 * Dialog display is delayed if delay greater than zero.
	 * @param delay number of milliseconds to delay displaying of the task dialog.  If the delay is
	 * greater than {@link #MAX_DELAY}, then the delay will be {@link #MAX_DELAY};
	 */
	public void show(int delay) {
		if (isModal()) {
			doShowModal(delay);
		}
		else {
			doShowNonModal(delay);
		}

	}

	private void doShowModal(int delay) {
		//
		// Note: we must block, since we are modal.  Clients want us to finish the task before
		//       returning
		//
		giveTheTaskThreadAChanceToComplete(delay);

		if (isCompleted()) {
			return;
		}

		doShow();
	}

	private void doShowNonModal(int delay) {
		//
		// Note: we must not block, as we are not modal.  Clients want control back.  Our job is
		//       only to show a progress dialog if enough time has elapsed.
		//
		GTimer.scheduleRunnable(delay, () -> {

			if (isCompleted()) {
				return;
			}

			doShow();
		});
	}

	protected void doShow() {
		SystemUtilities.runIfSwingOrPostSwingLater(() -> {
			DockingWindowManager.showDialog(centerOnComp, TaskDialog.this);
		});
	}

	private void giveTheTaskThreadAChanceToComplete(int delay) {

		delay = Math.min(delay, MAX_DELAY);
		int elapsedTime = 0;
		while (!isCompleted() && elapsedTime < delay) {
			try {
				Thread.sleep(SLEEPY_TIME);
			}
			catch (InterruptedException e) {
				// don't care; we will try again
			}
			elapsedTime += SLEEPY_TIME;
		}
	}

	public void dispose() {

		Runnable disposeTask = () -> {
			if (showTimer != null) {
				showTimer.stop();
				showTimer = null;
			}
		};

		SystemUtilities.runSwingNow(disposeTask);
	}

	@Override
	public synchronized void cancel() {
		if (monitorComponent.isCancelled()) {
			return;
		}
		// Mark as cancelled, must be detected by task which should terminate
		// and invoke setCompleted which will dismiss dialog.
		monitorComponent.cancel();
	}

	@Override
	public synchronized void clearCanceled() {
		monitorComponent.clearCanceled();
	}

	@Override
	public void checkCanceled() throws CancelledException {
		monitorComponent.checkCanceled();
	}

	@Override
	public void incrementProgress(long incrementAmount) {
		monitorComponent.incrementProgress(incrementAmount);
	}

	@Override
	public long getProgress() {
		return monitorComponent.getProgress();
	}

	@Override
	public void addCancelledListener(CancelledListener listener) {
		monitorComponent.addCancelledListener(listener);
	}

	@Override
	public void removeCancelledListener(CancelledListener listener) {
		monitorComponent.removeCancelledListener(listener);
	}

	@Override
	public synchronized TaskMonitor getSecondaryMonitor() {
		if (secondaryTaskMonitor == null) {
			secondaryTaskMonitor = new SecondaryTaskMonitor(this);
		}
		return secondaryTaskMonitor;
	}
}
