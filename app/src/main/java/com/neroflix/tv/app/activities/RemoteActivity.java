package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.KeyEvent;
import com.neroflix.tv.app.util.RemoteNavigationHelper;
import com.neroflix.tv.app.util.UniversalRemoteHandler;

/**
 * RemoteActivity - Base class for all TV activities with unified remote handling
 *
 * Features:
 * - Automatic remote action routing via onRemoteAction callback
 * - Standard DPAD/remote navigation for all activities
 * - Easy to extend - just override the remote action handlers you need
 * - Backwards compatible with old onTvKeyDown implementation
 *
 * Usage:
 * 1. Extend RemoteActivity instead of BaseTvActivity
 * 2. Implement createRemoteActionListener() to provide your remote handlers
 * 3. Override only the remote actions you care about (UP, DOWN, CENTER, etc)
 * 4. RemoteActivity handles all the UniversalRemoteHandler routing automatically
 *
 * Example:
 * <pre>
 *   public class MyActivity extends RemoteActivity {
 *       @Override
 *       protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
 *           return new RemoteNavigationHelper.RemoteActionAdapter() {
 *               @Override
 *               public boolean onRemoteDown() {
 *                   // Handle down navigation
 *                   moveSelectionDown();
 *                   return true;
 *               }
 *               @Override
 *               public boolean onRemoteCenter() {
 *                   // Handle selection
 *                   selectItem();
 *                   return true;
 *               }
 *           };
 *       }
 *   }
 * </pre>
 */
public abstract class RemoteActivity extends BaseTvActivity {

    private RemoteNavigationHelper.RemoteActionListener remoteActionListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Create remote listener for this activity
        remoteActionListener = createRemoteActionListener();
    }

    /**
     * Subclasses must implement this to provide remote action handlers
     * Return a RemoteActionListener or RemoteActionAdapter with handlers for remote actions
     *
     * @return RemoteActionListener implementation, or null for no custom handling
     */
    protected abstract RemoteNavigationHelper.RemoteActionListener createRemoteActionListener();

    /**
     * Main entry point for all remote actions
     * Routes normalized remote actions to appropriate handlers
     *
     * @param action Normalized action from UniversalRemoteHandler
     * @param keyCode Original Android key code
     * @param event KeyEvent for additional processing
     * @return true if action was consumed, false to let system handle it
     */
    @Override
    protected boolean onRemoteAction(@UniversalRemoteHandler.RemoteAction int action,
                                     int keyCode, KeyEvent event) {

        // Route to navigation helper handlers
        switch (action) {
            case UniversalRemoteHandler.ACTION_UP:
                return RemoteNavigationHelper.handleActionUp(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_DOWN:
                return RemoteNavigationHelper.handleActionDown(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_LEFT:
                return RemoteNavigationHelper.handleActionLeft(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_RIGHT:
                return RemoteNavigationHelper.handleActionRight(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_CENTER:
                return RemoteNavigationHelper.handleActionCenter(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_BACK:
                return RemoteNavigationHelper.handleActionBack(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_MENU:
                return RemoteNavigationHelper.handleActionMenu(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_INFO:
                return RemoteNavigationHelper.handleActionInfo(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_GUIDE:
                return RemoteNavigationHelper.handleActionGuide(this, remoteActionListener);
            case UniversalRemoteHandler.ACTION_HOME:
                return RemoteNavigationHelper.handleActionHome(this, remoteActionListener);
            default:
                return false;
        }
    }

}
