package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.appcompat.app.AlertDialog;

import static com.zoffcc.applications.trifa.CaptureService.getGeoMsg_proto_v2;
import static com.zoffcc.applications.trifa.CaptureService.remote_location_data;
import static com.zoffcc.applications.trifa.CaptureService.remote_location_overlays;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online;
import static com.zoffcc.applications.trifa.HelperGeneric.bytes_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.hex_to_bytes;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.map;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GEO_COORDS_CUSTOM_LOSSLESS_ID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GROUP_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_CHAT_ID_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;

/*
 * Location groups:
 *
 * everybody who joins a location group sees the live location of every other member on the map.
 * the group is a public Tox NGC group, the group ID (chat id) is all that is needed to join it.
 * every own location update is sent to all joined groups as a custom group packet,
 * using the same "TzGeo02" payload that is sent to friends, with the sender's own tox public key appended.
 * if the sender is also an online friend of ours we already get their location directly, so the group copy is ignored.
 */
public class GroupLocationShare
{
    private static final String TAG = "trifa.GroupLocShare";

    static final String LOCATION_GROUP_NAME = "ToLoShare";
    // appended after ":ENDGEO" in group location packets, followed by the sender's tox public key
    static final String GROUP_GEO_PUBKEY_MARKER = ":PK:";
    // group peer key -> group number, for every group member that has a marker on the map
    static final ConcurrentHashMap<String, Long> group_peer_markers = new ConcurrentHashMap<>();

    // -------- sending --------

    static void send_location_to_all_groups(Location location)
    {
        try
        {
            final long[] groups = MainActivity.tox_group_get_grouplist();
            if ((groups == null) || (groups.length == 0))
            {
                return;
            }

            final byte[] geo = getGeoMsg_proto_v2(location, location.getTime());
            byte[] suffix = new byte[0];
            final String my_pubkey = get_own_pubkey();
            if (my_pubkey != null)
            {
                suffix = (GROUP_GEO_PUBKEY_MARKER + my_pubkey).getBytes(StandardCharsets.UTF_8);
            }

            final byte[] data = new byte[geo.length + suffix.length];
            System.arraycopy(geo, 0, data, 0, geo.length);
            System.arraycopy(suffix, 0, data, geo.length, suffix.length);
            data[0] = (byte) GEO_COORDS_CUSTOM_LOSSLESS_ID;

            for (long group_num : groups)
            {
                MainActivity.tox_group_send_custom_packet(group_num, 1, data, data.length);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static String get_own_pubkey()
    {
        try
        {
            if ((global_my_toxid != null) && (global_my_toxid.length() >= (TOX_PUBLIC_KEY_SIZE * 2)))
            {
                return global_my_toxid.substring(0, TOX_PUBLIC_KEY_SIZE * 2).toUpperCase(Locale.ROOT);
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    // -------- receiving --------

    static void on_group_custom_packet(long group_number, long peer_id, byte[] data, long length)
    {
        try
        {
            if ((data == null) || (length < 2) || (data[0] != (byte) GEO_COORDS_CUSTOM_LOSSLESS_ID))
            {
                return;
            }

            String geo_text = new String(data, 1, (int) length - 1, StandardCharsets.UTF_8);
            final int pk_index = geo_text.indexOf(GROUP_GEO_PUBKEY_MARKER);
            if (pk_index >= 0)
            {
                final String sender_tox_pubkey = geo_text.substring(pk_index + GROUP_GEO_PUBKEY_MARKER.length());
                geo_text = geo_text.substring(0, pk_index);
                if (is_online_friend(sender_tox_pubkey))
                {
                    // HINT: we already get this location directly from the friend, don't show them twice
                    return;
                }
            }

            final String peer_key = MainActivity.tox_group_peer_get_public_key(group_number, peer_id);
            if ((peer_key == null) || (peer_key.length() < 10))
            {
                return;
            }

            String peer_name = null;
            try
            {
                peer_name = MainActivity.tox_group_peer_get_name(group_number, peer_id);
            }
            catch (Exception ignored)
            {
            }
            if ((peer_name == null) || (peer_name.isEmpty()))
            {
                peer_name = "group member";
            }

            group_peer_markers.put(peer_key, group_number);

            final byte[] geo_bytes = geo_text.getBytes(StandardCharsets.UTF_8);
            final byte[] geo_packet = new byte[geo_bytes.length + 1];
            geo_packet[0] = (byte) GEO_COORDS_CUSTOM_LOSSLESS_ID;
            System.arraycopy(geo_bytes, 0, geo_packet, 1, geo_bytes.length);
            MainActivity.process_incoming_location_packet(peer_key, peer_name, geo_packet, geo_packet.length);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static boolean is_online_friend(String tox_pubkey)
    {
        try
        {
            if ((tox_pubkey == null) || (tox_pubkey.length() != (TOX_PUBLIC_KEY_SIZE * 2)))
            {
                return false;
            }
            final long friend_num = MainActivity.tox_friend_by_public_key(tox_pubkey);
            if ((friend_num < 0) || (friend_num >= UINT32_MAX_JAVA))
            {
                return false;
            }
            return (is_friend_online(friend_num) != 0);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void on_group_peer_exit(long group_number, long peer_id)
    {
        try
        {
            final String peer_key = MainActivity.tox_group_peer_get_public_key(group_number, peer_id);
            if ((peer_key != null) && (peer_key.length() >= 10))
            {
                remove_marker(peer_key);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void on_group_self_join(long group_number)
    {
        display_toast("Connected to location group", false, 0);
    }

    static void on_group_join_fail(long group_number, int a_Tox_Group_Join_Fail)
    {
        display_toast("Could not join location group (error " + a_Tox_Group_Join_Fail + ")", true, 0);
    }

    private static void remove_group_markers(long group_number)
    {
        for (Map.Entry<String, Long> entry : new ArrayList<>(group_peer_markers.entrySet()))
        {
            if (entry.getValue() == group_number)
            {
                remove_marker(entry.getKey());
            }
        }
    }

    private static void remove_marker(final String peer_key)
    {
        group_peer_markers.remove(peer_key);
        final Runnable myRunnable = () -> {
            try
            {
                final CaptureService.remote_location_overlay_entry ro = remote_location_overlays.remove(peer_key);
                remote_location_data.remove(peer_key);
                if ((ro != null) && (map != null))
                {
                    map.getOverlays().remove(ro.remote_location_overlay);
                    map.invalidate();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    // -------- create / join / leave --------

    static String get_group_id(long group_number)
    {
        try
        {
            final ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
            if (MainActivity.tox_group_get_chat_id(group_number, groupid_buf) == 0)
            {
                final byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
                groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
                return bytes_to_hex(groupid_buffer).toUpperCase(Locale.ROOT);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private static String my_peer_name()
    {
        if ((global_my_name != null) && (!global_my_name.trim().isEmpty()))
        {
            return global_my_name.trim();
        }
        return RandomNameGenerator.getFullName(new Random());
    }

    static long create_group()
    {
        final long group_num = MainActivity.tox_group_new(
                ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value, LOCATION_GROUP_NAME,
                my_peer_name());
        Log.i(TAG, "create_group:group_num=" + group_num);
        if ((group_num < 0) || (group_num >= UINT32_MAX_JAVA))
        {
            return -1;
        }
        update_savedata_file_wrapper();
        return group_num;
    }

    /*
     * returns the 64 hex char group ID found in the given text (it may be a whole shared message), or null
     */
    static String extract_group_id(String text)
    {
        if (text == null)
        {
            return null;
        }
        final Matcher m = Pattern.compile("[0-9a-fA-F]{" + (TOX_GROUP_CHAT_ID_SIZE * 2) + "}").matcher(text);
        if (m.find())
        {
            return m.group().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    static long join_group(String group_id)
    {
        final ByteBuffer join_chat_id_buffer = ByteBuffer.allocateDirect(TOX_GROUP_CHAT_ID_SIZE);
        join_chat_id_buffer.put(hex_to_bytes(group_id));
        join_chat_id_buffer.rewind();
        final long group_num = MainActivity.tox_group_join(join_chat_id_buffer, TOX_GROUP_CHAT_ID_SIZE,
                                                           my_peer_name(), null);
        Log.i(TAG, "join_group:group_num=" + group_num);
        if ((group_num < 0) || (group_num >= UINT32_MAX_JAVA))
        {
            return -1;
        }
        update_savedata_file_wrapper();
        return group_num;
    }

    static void leave_group(long group_number)
    {
        MainActivity.tox_group_leave(group_number, null);
        update_savedata_file_wrapper();
        remove_group_markers(group_number);
    }

    // -------- UI --------

    static void show_location_group_dialog(final Activity a)
    {
        if (!TrifaToxService.is_tox_started)
        {
            display_toast("Tox is not running", false, 0);
            return;
        }

        long[] groups = null;
        try
        {
            groups = MainActivity.tox_group_get_grouplist();
        }
        catch (Exception ignored)
        {
        }

        if ((groups == null) || (groups.length == 0))
        {
            show_not_in_group_dialog(a);
        }
        else
        {
            show_in_group_dialog(a, groups[0]);
        }
    }

    private static void show_not_in_group_dialog(final Activity a)
    {
        new AlertDialog.Builder(a).
                setTitle("Location group").
                setMessage("Everyone in a location group sees everyone else's live location on the map.\n\n" +
                           "Create a new group and share its ID, or join a group with an ID someone sent you.").
                setPositiveButton("Create group", (dialog, which) -> {
                    final long group_num = create_group();
                    if (group_num < 0)
                    {
                        display_toast("Could not create the group", true, 0);
                        return;
                    }
                    show_in_group_dialog(a, group_num);
                }).
                setNeutralButton("Join group", (dialog, which) -> show_join_dialog(a)).
                setNegativeButton("Cancel", null).
                show();
    }

    private static void show_join_dialog(final Activity a)
    {
        final EditText input = new EditText(a);
        input.setHint("Group ID");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        try
        {
            final ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if ((clipboard != null) && (clipboard.hasPrimaryClip()) && (clipboard.getPrimaryClip().getItemCount() > 0))
            {
                final CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).getText();
                final String clip_id = extract_group_id(clip == null ? null : clip.toString());
                if (clip_id != null)
                {
                    input.setText(clip_id);
                }
            }
        }
        catch (Exception ignored)
        {
        }

        new AlertDialog.Builder(a).
                setTitle("Join location group").
                setMessage("Paste the group ID you were sent:").
                setView(input).
                setPositiveButton("Join", (dialog, which) -> {
                    final String group_id = extract_group_id(input.getText().toString());
                    if (group_id == null)
                    {
                        display_toast("That is not a valid group ID", true, 0);
                        return;
                    }
                    if (join_group(group_id) < 0)
                    {
                        display_toast("Could not join the group", true, 0);
                        return;
                    }
                    display_toast("Joining group ...", false, 0);
                }).
                setNegativeButton("Cancel", null).
                show();
    }

    private static void show_in_group_dialog(final Activity a, final long group_num)
    {
        final String group_id = get_group_id(group_num);
        long peers = -1;
        try
        {
            peers = MainActivity.tox_group_peer_count(group_num);
        }
        catch (Exception ignored)
        {
        }
        final boolean connected = (MainActivity.tox_group_is_connected(group_num) == 1);

        String msg = "You share your live location with everyone in this group, and see theirs on the map.\n\n";
        if (peers >= 0)
        {
            msg = msg + "People in group: " + peers + "\n";
        }
        msg = msg + "Status: " + (connected ? "connected" : "connecting ...") + "\n\n";
        msg = msg + "Group ID (send it to the people who should join):\n" + group_id;

        new AlertDialog.Builder(a).
                setTitle("Location group").
                setMessage(msg).
                setPositiveButton("Share ID", (dialog, which) -> share_group_id(a, group_id)).
                setNeutralButton("Leave group", (dialog, which) -> confirm_leave_group(a, group_num)).
                setNegativeButton("Close", null).
                show();
    }

    private static void share_group_id(final Activity a, final String group_id)
    {
        if (group_id == null)
        {
            return;
        }
        try
        {
            final ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("ToLoShare group ID", group_id));
        }
        catch (Exception ignored)
        {
        }

        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "Join my ToLoShare location group with this ID:\n" + group_id);
        a.startActivity(Intent.createChooser(send, "Share group ID"));
    }

    private static void confirm_leave_group(final Activity a, final long group_num)
    {
        new AlertDialog.Builder(a).
                setTitle("Leave location group?").
                setMessage("You will stop sharing your location with this group and stop seeing its members.").
                setPositiveButton("Leave", (dialog, which) -> {
                    leave_group(group_num);
                    display_toast("Left the location group", false, 0);
                }).
                setNegativeButton("Cancel", null).
                show();
    }
}
