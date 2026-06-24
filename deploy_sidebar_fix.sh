#!/bin/bash
set -e
REPO_DIR=$(pwd)
echo "📁 Working in: $REPO_DIR"

cat > app/src/main/res/drawable/ic_menu_toggle.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,6 L20,6"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,12 L20,12"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,18 L20,18"/>
</vector>
EOF
echo "✅ ic_menu_toggle.xml created"

cat > app/src/main/res/drawable/ic_menu_collapse.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,6 L14,6"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,12 L20,12"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M4,18 L14,18"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:fillColor="@android:color/transparent"
        android:strokeLineCap="round"
        android:pathData="M18,9 L21,6 L18,3"/>
</vector>
EOF
echo "✅ ic_menu_collapse.xml created"

python3 - << 'PYEOF'
path = "app/src/main/res/layout/activity_main.xml"
with open(path, "r") as f:
    content = f.read()

old = '''        <!-- Toggle expand/collapse button -->
        <TextView
            android:id="@+id/nav_toggle_btn"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:text="&gt;&gt;"
            android:textColor="#FFFFFF"
            
            android:gravity="center"
            
            android:focusable="true"
            android:clickable="true"
            android:focusableInTouchMode="true"
            android:textSize="16sp"
            android:textStyle="bold"
            android:background="#E50914"
            android:nextFocusDown="@id/nav_recycler"
            android:nextFocusRight="@id/filter_bar"/>'''

new = '''        <!-- Toggle expand/collapse button -->
        <ImageButton
            android:id="@+id/nav_toggle_btn"
            android:layout_width="match_parent"
            android:layout_height="52dp"
            android:src="@drawable/ic_menu_toggle"
            android:scaleType="center"
            android:background="@drawable/nav_item_focus_bg"
            android:contentDescription="Toggle sidebar"
            android:focusable="true"
            android:clickable="true"
            android:focusableInTouchMode="true"
            android:nextFocusDown="@id/nav_recycler"
            android:nextFocusRight="@id/filter_bar"/>'''

if old in content:
    content = content.replace(old, new)
    with open(path, "w") as f:
        f.write(content)
    print("✅ activity_main.xml patched")
else:
    print("⚠️  activity_main.xml — block not found, may need manual edit")
PYEOF

python3 - << 'PYEOF'
path = "app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java"
with open(path, "r") as f:
    content = f.read()

c1 = content.replace(
    'TextView toggleBtn = findViewById(R.id.nav_toggle_btn);',
    'android.widget.ImageButton toggleBtn = findViewById(R.id.nav_toggle_btn);'
)
c2 = c1.replace(
    'toggleBtn.setText(expanded ? "<<" : ">>"); ',
    'toggleBtn.setImageResource(expanded ? R.drawable.ic_menu_collapse : R.drawable.ic_menu_toggle);'
)

if c2 != content:
    with open(path, "w") as f:
        f.write(c2)
    print("✅ MainActivity.java patched")
else:
    print("⚠️  MainActivity.java — nothing changed, may already be patched")
PYEOF

git add \
  app/src/main/res/drawable/ic_menu_toggle.xml \
  app/src/main/res/drawable/ic_menu_collapse.xml \
  app/src/main/res/layout/activity_main.xml \
  app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java

git commit -m "fix: replace sidebar toggle TextView with ImageButton icon"
git push
echo "🎉 Done! Check GitHub Actions for the build."
