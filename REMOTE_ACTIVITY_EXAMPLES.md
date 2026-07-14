# Remote Activity Implementation Examples

This document provides complete working examples of converting different types of activities to use the new RemoteActivity base class.

## Example 1: Simple List Navigation (DetailActivity Pattern)

**Use Case**: Activity with a simple list that responds to UP/DOWN/CENTER/BACK

```java
package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.RemoteNavigationHelper;

public class DetailActivity extends RemoteActivity {

    private RecyclerView listView;
    private int selectedIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        listView = findViewById(R.id.detail_list);
    }

    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                if (selectedIndex > 0) {
                    selectedIndex--;
                    scrollToSelection();
                }
                return true;
            }

            @Override
            public boolean onRemoteDown() {
                int max = listView.getAdapter().getItemCount() - 1;
                if (selectedIndex < max) {
                    selectedIndex++;
                    scrollToSelection();
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

    private void scrollToSelection() {
        listView.smoothScrollToPosition(selectedIndex);
    }

    private void selectItem(int index) {
        // Handle item selection
    }
}
```

## Example 2: Grid Navigation (Gallery/Poster Grid)

**Use Case**: Activity with a grid layout that responds to UP/DOWN/LEFT/RIGHT/CENTER

```java
package com.neroflix.tv.app.activities;

import android.os.Bundle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.RemoteNavigationHelper;

public class GenreActivity extends RemoteActivity {

    private RecyclerView gridView;
    private GridLayoutManager gridManager;
    private int selectedIndex = 0;
    private int COLUMNS = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_genre);
        gridView = findViewById(R.id.genre_grid);
        gridManager = new GridLayoutManager(this, COLUMNS);
        gridView.setLayoutManager(gridManager);
    }

    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                int newIndex = selectedIndex - COLUMNS;
                if (newIndex >= 0) {
                    selectedIndex = newIndex;
                    gridView.smoothScrollToPosition(selectedIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteDown() {
                int itemCount = gridView.getAdapter().getItemCount();
                int newIndex = selectedIndex + COLUMNS;
                if (newIndex < itemCount) {
                    selectedIndex = newIndex;
                    gridView.smoothScrollToPosition(selectedIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteLeft() {
                if (selectedIndex > 0) {
                    selectedIndex--;
                    gridView.smoothScrollToPosition(selectedIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteRight() {
                int itemCount = gridView.getAdapter().getItemCount();
                if (selectedIndex < itemCount - 1) {
                    selectedIndex++;
                    gridView.smoothScrollToPosition(selectedIndex);
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

    private void selectItem(int index) {
        // Handle grid item selection
    }
}
```

## Example 3: Multiple Zones (Sidebar + Content)

**Use Case**: Activity with multiple navigation zones (like MainActivity)

```java
package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.RemoteNavigationHelper;

public class ZonedActivity extends RemoteActivity {

    private enum FocusZone { SIDEBAR, CONTENT }
    private FocusZone currentZone = FocusZone.SIDEBAR;

    private RecyclerView sidebarView;
    private RecyclerView contentView;
    private int sidebarIndex = 0;
    private int contentIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zoned);
        sidebarView = findViewById(R.id.sidebar);
        contentView = findViewById(R.id.content);
    }

    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                if (currentZone == FocusZone.SIDEBAR) {
                    if (sidebarIndex > 0) {
                        sidebarIndex--;
                        updateSidebarFocus();
                    }
                } else {
                    if (contentIndex > 0) {
                        contentIndex--;
                        updateContentFocus();
                    }
                }
                return true;
            }

            @Override
            public boolean onRemoteDown() {
                if (currentZone == FocusZone.SIDEBAR) {
                    int max = sidebarView.getAdapter().getItemCount() - 1;
                    if (sidebarIndex < max) {
                        sidebarIndex++;
                        updateSidebarFocus();
                    }
                } else {
                    int max = contentView.getAdapter().getItemCount() - 1;
                    if (contentIndex < max) {
                        contentIndex++;
                        updateContentFocus();
                    }
                }
                return true;
            }

            @Override
            public boolean onRemoteLeft() {
                if (currentZone == FocusZone.CONTENT) {
                    currentZone = FocusZone.SIDEBAR;
                    updateSidebarFocus();
                }
                return true;
            }

            @Override
            public boolean onRemoteRight() {
                if (currentZone == FocusZone.SIDEBAR) {
                    currentZone = FocusZone.CONTENT;
                    updateContentFocus();
                }
                return true;
            }

            @Override
            public boolean onRemoteCenter() {
                if (currentZone == FocusZone.SIDEBAR) {
                    selectSidebarItem(sidebarIndex);
                } else {
                    selectContentItem(contentIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteBack() {
                finish();
                return true;
            }

            @Override
            public boolean onRemoteMenu() {
                openMenu();
                return true;
            }
        };
    }

    private void updateSidebarFocus() {
        sidebarView.smoothScrollToPosition(sidebarIndex);
    }

    private void updateContentFocus() {
        contentView.smoothScrollToPosition(contentIndex);
    }

    private void selectSidebarItem(int index) {
        // Handle sidebar selection
    }

    private void selectContentItem(int index) {
        // Handle content selection
    }

    private void openMenu() {
        // Open menu/options
    }
}
```

## Example 4: Web View Scrolling (DownloadActivity - Already Converted)

**Use Case**: Activity hosting a WebView with scroll controls

```java
package com.neroflix.tv.app.activities;

import android.webkit.WebView;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.RemoteNavigationHelper;

public class WebActivity extends RemoteActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);
        webView = findViewById(R.id.webview);
    }

    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                webView.scrollBy(0, -150);
                return true;
            }

            @Override
            public boolean onRemoteDown() {
                webView.scrollBy(0, 150);
                return true;
            }

            @Override
            public boolean onRemoteLeft() {
                webView.scrollBy(-150, 0);
                return true;
            }

            @Override
            public boolean onRemoteRight() {
                webView.scrollBy(150, 0);
                return true;
            }

            @Override
            public boolean onRemoteCenter() {
                // Click element at center of screen
                webView.evaluateJavascript(
                    "var el = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);" +
                    "if(el) el.click();", null);
                return true;
            }

            @Override
            public boolean onRemoteBack() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        };
    }
}
```

## Example 5: Search Activity with Dynamic Results

**Use Case**: Activity with search field and results list

```java
package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.RemoteNavigationHelper;

public class SearchActivity extends RemoteActivity {

    private enum FocusZone { SEARCH_BOX, RESULTS }
    private FocusZone currentZone = FocusZone.SEARCH_BOX;

    private EditText searchBox;
    private RecyclerView resultsView;
    private int selectedResultIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        searchBox = findViewById(R.id.search_input);
        resultsView = findViewById(R.id.search_results);
        searchBox.requestFocus();
    }

    @Override
    protected RemoteNavigationHelper.RemoteActionListener createRemoteActionListener() {
        return new RemoteNavigationHelper.RemoteActionAdapter() {
            
            @Override
            public boolean onRemoteUp() {
                if (currentZone == FocusZone.RESULTS && selectedResultIndex > 0) {
                    selectedResultIndex--;
                    resultsView.smoothScrollToPosition(selectedResultIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteDown() {
                if (currentZone == FocusZone.SEARCH_BOX) {
                    currentZone = FocusZone.RESULTS;
                    selectedResultIndex = 0;
                    resultsView.requestFocus();
                } else {
                    int max = resultsView.getAdapter().getItemCount() - 1;
                    if (selectedResultIndex < max) {
                        selectedResultIndex++;
                        resultsView.smoothScrollToPosition(selectedResultIndex);
                    }
                }
                return true;
            }

            @Override
            public boolean onRemoteCenter() {
                if (currentZone == FocusZone.SEARCH_BOX) {
                    // Activate search
                    performSearch();
                } else {
                    // Select result
                    selectResult(selectedResultIndex);
                }
                return true;
            }

            @Override
            public boolean onRemoteBack() {
                if (currentZone == FocusZone.RESULTS) {
                    currentZone = FocusZone.SEARCH_BOX;
                    searchBox.requestFocus();
                } else {
                    finish();
                }
                return true;
            }
        };
    }

    private void performSearch() {
        // Execute search with text from searchBox
    }

    private void selectResult(int index) {
        // Handle result selection
    }
}
```

## Migration Checklist

When converting an activity:

- [ ] Change `extends BaseTvActivity` to `extends RemoteActivity`
- [ ] Add import for `RemoteNavigationHelper`
- [ ] Add `createRemoteActionListener()` method
- [ ] Return `RemoteActionAdapter` instance
- [ ] Override only the remote actions you need
- [ ] Move logic from old `onTvKeyDown()` to appropriate action handlers
- [ ] Remove the old `onTvKeyDown()` method
- [ ] Test all navigation paths with remote
- [ ] Verify smooth focus transitions
- [ ] Test with multiple remote types if possible

## Common Patterns

### Pattern 1: List Navigation
```java
public boolean onRemoteUp() {
    if (index > 0) {
        index--;
        updateFocus();
    }
    return true;
}

public boolean onRemoteDown() {
    if (index < maxItems - 1) {
        index++;
        updateFocus();
    }
    return true;
}
```

### Pattern 2: Zone Switching
```java
public boolean onRemoteLeft() {
    if (currentZone == ZONE_CONTENT) {
        currentZone = ZONE_SIDEBAR;
        updateFocus();
    }
    return true;
}

public boolean onRemoteRight() {
    if (currentZone == ZONE_SIDEBAR) {
        currentZone = ZONE_CONTENT;
        updateFocus();
    }
    return true;
}
```

### Pattern 3: Selection Action
```java
public boolean onRemoteCenter() {
    selectItem(selectedIndex);
    return true;
}
```

## Testing Tips

1. **Test each remote action** - UP, DOWN, LEFT, RIGHT, CENTER, BACK
2. **Verify boundaries** - Can't go past start/end of list
3. **Check transitions** - Smooth scrolling between zones
4. **Test all remotes** - If available, test with physical remotes
5. **Verify focus** - Visual indication of selected item
6. **Edge cases** - Empty lists, single item, disabled items

---

All examples follow the unified remote handling pattern and automatically support 50+ different remote types!
