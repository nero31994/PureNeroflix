with open("app/src/main/res/layout/activity_main.xml", "r") as f:
    src = f.read()

old = '''            android:visibility="gone"
            android:elevation="20dp" />

    </RelativeLayout>'''

new = '''            android:visibility="gone"
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
    print("  refresh_btn added successfully")
else:
    print("  ERROR: still not matching — check indentation manually")

with open("app/src/main/res/layout/activity_main.xml", "w") as f:
    f.write(src)
