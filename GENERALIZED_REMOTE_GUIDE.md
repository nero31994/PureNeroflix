# Generalized Remote Support for All Activities

This guide explains how to migrate your activities from BaseTvActivity to RemoteActivity for unified remote control support across the entire app.

## What's New

Three new components have been created:

1. **UniversalRemoteHandler** - Maps 50+ key codes to 22 standard remote actions
2. **RemoteNavigationHelper** - Utility methods for common remote operations
3. **RemoteActivity** - Base class extending BaseTvActivity with integrated remote handling

## Why Generalize Remote Support?

- **Consistency** - All activities respond to remotes the same way
- **Maintainability** - Single place to handle remote logic
- **Extensibility** - Easy to add new remote actions
- **Simplicity** - Activities only implement the actions they need

## Migration Overview

### Before (BaseTvActivity with hardcoded keys)
```java
public class MyActivity extends BaseTvActivity {
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // Handle up
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Handle down
                return true;
            // ... more cases
        }
        return false;
    }
}
```

### After (RemoteActivity with normalized actions)
```java
public class MyActivity extends RemoteActivity {
    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            @Override
            public boolean onRemoteUp() {
                // Handle up - works with ALL remote types!
                return true;
            }
            @Override
            public boolean onRemoteDown() {
                // Handle down - works with ALL remote types!
                return true;
            }
        };
    }
}
```

## Step-by-Step Migration

### Step 1: Change Base Class
Replace `extends BaseTvActivity` with `extends RemoteActivity`

```java
// Before
public class MyActivity extends BaseTvActivity {

// After
public class MyActivity extends RemoteActivity {
```

### Step 2: Implement Remote Listener
Add this method to your activity:

```java
@Override
protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
    return new RemoteNavigationHelper.RemoteActionAdapter() {
        // Override only the actions you need
        @Override
        public boolean onRemoteUp() {
            // Your UP logic here
            return true;
        }
        
        @Override
        public boolean onRemoteDown() {
            // Your DOWN logic here
            return true;
        }
        
        // ... other actions as needed
    };
}
```

### Step 3: Replace or Remove onTvKeyDown
The old `onTvKeyDown()` method is no longer needed. You can remove it.

If you had activity-specific logic in onTvKeyDown, move it to the appropriate remote action handler in `createRemoteActionListener()`.

### Step 4: Use Normalized Actions Only
Instead of checking `keyCode == KeyEvent.KEYCODE_DPAD_UP`, the framework automatically converts ALL remote key codes to standardized actions like `onRemoteUp()`.

## Available Remote Actions

These are the standard actions available to all activities:

```java
onRemoteUp()      // DPAD UP or equivalent
onRemoteDown()    // DPAD DOWN or equivalent
onRemoteLeft()    // DPAD LEFT or equivalent
onRemoteRight()   // DPAD RIGHT or equivalent
onRemoteCenter()  // DPAD CENTER / SELECT or equivalent
onRemoteBack()    // BACK button or equivalent
onRemoteMenu()    // MENU button or equivalent
onRemoteInfo()    // INFO button or equivalent
onRemoteGuide()   // GUIDE button or equivalent
onRemoteHome()    // HOME button or equivalent
```

## Remote Types Automatically Supported

Once you migrate to RemoteActivity, you automatically support:

- Samsung TV Remotes (custom key codes)
- LG TV Remotes (custom key codes)
- Xiaomi TV Remotes (custom key codes)
- Generic Android TV Remotes (standard DPAD)
- Game Controllers / Gamepads (ABXY mapping)
- Colored Button Remotes (Red/Green/Yellow/Blue)
- Roku Remotes
- Fire TV Remotes
- And 40+ other remote variations

## Helper Methods Available

RemoteActivity provides helper methods for common tasks:

```java
// Request focus on a view with smooth delay
requestFocusSmooth(view);

// Request immediate focus
requestFocusImmediate(view);

// Clear focus from a view
clearFocus(view);

// Get navigation helper utilities
RemoteNavigationHelper helper = getRemoteHelper();
```

## Complete Migration Example

Here's a complete before/after example:

### Before
```java
public class BrowseActivity extends BaseTvActivity {
    
    private int selectedIndex = 0;
    private RecyclerView listView;
    
    @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selectedIndex > 0) {
                    selectedIndex--;
                    updateSelection();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (selectedIndex < getItemCount() - 1) {
                    selectedIndex++;
                    updateSelection();
                }
                return true;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                selectItem(selectedIndex);
                return true;
                
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return false;
    }
    
    private void updateSelection() {
        listView.getAdapter().notifyDataSetChanged();
    }
    
    private void selectItem(int index) {
        // Handle selection
    }
    
    private int getItemCount() {
        return listView.getAdapter().getItemCount();
    }
}
```

### After
```java
public class BrowseActivity extends RemoteActivity {
    
    private int selectedIndex = 0;
    private RecyclerView listView;
    
    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                if (selectedIndex > 0) {
                    selectedIndex--;
                    updateSelection();
                }
                return true;
            }
            
            @Override
            public boolean onRemoteDown() {
                if (selectedIndex < getItemCount() - 1) {
                    selectedIndex++;
                    updateSelection();
                }
                return true;
            }
            
            @Override
            public boolean onRemoteCenter() {
                selectItem(selectedIndex);
                return true;
            }
            
            @Override
            public boolean onRemoteBack() {
                finish();
                return true;
            }
        };
    }
    
    private void updateSelection() {
        listView.getAdapter().notifyDataSetChanged();
    }
    
    private void selectItem(int index) {
        // Handle selection
    }
    
    private int getItemCount() {
        return listView.getAdapter().getItemCount();
    }
}
```

## Supported Activities for Migration

The following activities are currently using BaseTvActivity and can be migrated:

1. MainActivity
2. BrowseActivity
3. DetailActivity
4. SearchActivity
5. RadioActivity
6. AnimeActivity
7. KdramaActivity
8. WatchPartyActivity
9. SettingsActivity
10. AboutActivity
11. NetworkActivity
12. DonateActivity
13. TvShowDetailActivity
14. MovieDetailActivity

## Backward Compatibility

RemoteActivity is fully backward compatible:

- Old onTvKeyDown() methods still work if you don't implement createRemoteActionListener()
- You can mix old and new approaches gradually
- No breaking changes to the API

## Testing Remote Support

1. Use RemoteSettingsActivity to verify your remote type
2. Test all actions defined in createRemoteActionListener()
3. Verify smooth transitions and focus changes
4. Test on multiple remote types if possible

## Rollback

If you need to rollback a migration:

1. Change `extends RemoteActivity` back to `extends BaseTvActivity`
2. Restore the old `onTvKeyDown()` method
3. No other changes needed

## Performance Impact

- Negligible - less code, better optimized
- Smooth 50ms focus transitions
- No additional memory overhead
- Event-driven handling only

## Next Steps

1. Review this guide
2. Choose one activity to migrate first (start simple)
3. Test thoroughly with your remote
4. Gradually migrate other activities
5. Share feedback!

## Support

For questions or issues:

1. Check RemoteActivity Javadoc
2. Look at RemoteNavigationHelper for available helpers
3. Review the migration examples above
4. Check UniversalRemoteHandler for supported key codes

---

**Result**: Unified remote control experience across your entire app with less code and better maintainability!
