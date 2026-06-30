with open("app/src/main/res/layout-land/activity_main.xml", "r") as f:
    src = f.read()

old = '''        <ProgressBar android:id="@+id/progress_bar" android:layout_width="48dp" android:layout_height="48dp" android:layout_centerInParent="true" android:indeterminateTint="@color/neon_red" android:visibility="gone" />

    </RelativeLayout>'''

new = '''        <ProgressBar android:id="@+id/progress_bar" android:layout_width="48dp" android:layout_height="48dp" android:layout_centerInParent="true" android:indeterminateTint="@color/neon_red" android:visibility="gone" />

        <TextView
            android:id="@+id/offline_banner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:text="⚠ You're offline — check your internet connection"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:gravity="center"
            android:padding="10dp"
            android:background="#CC8B0000"
            android:visibility="gone"
            android:elevation="20dp" />

        <TextView
            android:id="@+id/refresh_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:layout_alignParentEnd="true"
            android:layout_margin="12dp"
            android:text="⟳ Refresh"
            android:textColor="@color/text_primary"
            android:textSize="12sp"
            android:padding="8dp"
            android:focusable="true"
            android:background="@drawable/nav_item_focus_bg"
            android:elevation="21dp" />

    </RelativeLayout>'''

if old in src:
    src = src.replace(old, new, 1)
    print("  layout-land/activity_main.xml: offline_banner + refresh_btn added")
else:
    print("  ERROR: pattern not found — structure may differ from expected")

with open("app/src/main/res/layout-land/activity_main.xml", "w") as f:
    f.write(src)
