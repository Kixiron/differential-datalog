package ddlogapi;

import java.util.*;
import java.util.function.*;
import java.nio.ByteBuffer;
import java.io.IOException;

/**
 * Java wrapper around Differential Datalog C API that manipulates
 * DDlog programs.
 */
public class DDlogAPI {
    static {
        System.loadLibrary("ddlogapi");
    }

    /**
     * The C ddlog API
     */
    native long ddlog_run(boolean storeData, int workers, String callbackName) throws DDlogException;
    static native int ddlog_record_commands(long hprog, String filename, boolean append) throws DDlogException, IOException;
    static native void ddlog_stop_recording(long hprog, int fd) throws DDlogException;
    static native void ddlog_dump_input_snapshot(long hprog, String filename, boolean append) throws DDlogException, IOException;
    native void dump_table(long hprog, int table, String callbackMethod) throws DDlogException;
    static native void ddlog_stop(long hprog, long callbackHandle) throws DDlogException;
    static native void ddlog_transaction_start(long hprog) throws DDlogException;
    static native void ddlog_transaction_commit(long hprog) throws DDlogException;
    native void ddlog_transaction_commit_dump_changes(long hprog, String callbackName) throws DDlogException;
    static native void ddlog_transaction_commit_dump_changes_to_flatbuf(long hprog, FlatBufDescr fb) throws DDlogException;
    static native void ddlog_flatbuf_free(ByteBuffer buf, long size, long offset);
    static native void ddlog_transaction_rollback(long hprog) throws DDlogException;
    static native void ddlog_apply_updates(long hprog, long[] upds) throws DDlogException;
    static native void ddlog_apply_updates_from_flatbuf(long hprog, byte[] bytes, int position) throws DDlogException;
    static native int ddlog_clear_relation(long hprog, int relid);
    static native String ddlog_profile(long hprog);
    static native void ddlog_enable_cpu_profiling(long hprog, boolean enable) throws DDlogException;
    static native long ddlog_log_replace_callback(int module, long old_cbinfo, ObjIntConsumer<String> cb, int max_level);

    static native void ddlog_free(long handle);

    /* All the following methods return in fact `ddlog_record` handles */

    // Constructors
    static native long ddlog_bool(boolean b);
    static native long ddlog_i64(long v);
    static native long ddlog_int(byte[] v);
    static native long ddlog_string(String s);
    static native long ddlog_tuple(long[] handles);
    static native long ddlog_vector(long[] handles);
    static native long ddlog_set(long[] handles);
    static native long ddlog_map(long[] handles);
    static native long ddlog_pair(long handle1, long handle2);
    static native long ddlog_struct(String constructor, long[] handles);
    // Getters
    static native int ddlog_get_table_id(String table);
    static native boolean ddlog_is_bool(long handle);
    static native boolean ddlog_get_bool(long handle);
    static native boolean ddlog_is_int(long handle);
    static native long ddlog_get_int(long handle, byte[] buf);
    static native long ddlog_get_i64(long handle);
    static native boolean ddlog_is_string(long handle);
    static native String ddlog_get_str(long handle);
    static native boolean ddlog_is_tuple(long handle);
    static native int ddlog_get_tuple_size(long handle);
    static native long ddlog_get_tuple_field(long tup, int i);
    static native boolean ddlog_is_vector(long handle);
    static native int ddlog_get_vector_size(long handle);
    static native long ddlog_get_vector_elem(long vec, int idx);
    static native boolean ddlog_is_set(long handle);
    static native int ddlog_get_set_size(long handle);
    static native long ddlog_get_set_elem(long set, int i);
    static native boolean ddlog_is_map(long handle);
    static native int ddlog_get_map_size(long handle);
    static native long ddlog_get_map_key(long handle, int i);
    static native long ddlog_get_map_val(long handle, int i);
    static native boolean ddlog_is_struct(long handle);
    static native String ddlog_get_constructor(long handle);
    static native long ddlog_get_struct_field(long handle, int i);

    static native long ddlog_insert_cmd(int table, long recordHandle);
    static native long ddlog_delete_val_cmd(int table, long recordHandle);
    static native long ddlog_delete_key_cmd(int table, long recordHandle);

    // This is a handle to the program; it wraps a void*.
    private final long hprog;
    // This stores a C pointer which is deallocated when the program stops.
    public long callbackHandle;

    // File descriptor used to record DDlog command log
    private int record_fd = -1;

    // Maps table names to table IDs
    private final Map<String, Integer> tableId;
    // Callback to invoke for each modified record on commit.
    // The command supplied to the callback can only have an Insert or DeleteValue 'kind'.
    // This callback can be invoked simultaneously from multiple threads.
    private final Consumer<DDlogCommand<DDlogRecord>> commitCallback;

    // Callback to invoke for each modified record on commit_dump_changes.
    // The command supplied to a callback can only have an Insert or DeleteValue 'kind'.
    private Consumer<DDlogCommand<DDlogRecord>> deltaCallback;

    // Callback to invoke for each record on `DDlogAPI.dump()`.
    // The callback is invoked sequentially by the same DDlog worker thread.
    private Consumer<DDlogRecord> dumpCallback;

    // Stores pointer to `struct CallbackInfo` for each registered logging
    // callback.  This is needed so that we can deallocate the `CallbackInfo*`
    // when removing on changing the callback.
    private static Map<Integer, Long> logCBInfo = new HashMap<>();

    /**
     * Create an API to access the DDlog program.
     * @param workers   number of threads the DDlog program can use
     * @param storeData If true the DDlog background program will store a copy
     *                  of all tables, which can be obtained with "dump".
     * @param callback  A method that is invoked for every tuple added or deleted to
     *                  an output table.  The command argument indicates the table,
     *                  whether it is deletion or insertion, and the actual value
     *                  that is being inserted or deleted.  This callback is invoked
     *                  many times, on potentially different threads, when the "commit"
     *                  API function is called.
     */
    public DDlogAPI(int workers, Consumer<DDlogCommand<DDlogRecord>> callback, boolean storeData) 
            throws DDlogException {
        this.tableId = new HashMap<String, Integer>();
        String onCommit = callback == null ? null : "onCommit";
        this.commitCallback = callback;
        this.hprog = this.ddlog_run(storeData, workers, onCommit);
    }

    /// Callback invoked from commit.
    void onCommit(int tableid, long handle, long w) {
        if (this.commitCallback != null) {
            DDlogCommand.Kind kind = w > 0 ? DDlogCommand.Kind.Insert : DDlogCommand.Kind.DeleteVal;
            for (long i = 0; i < java.lang.Math.abs(w); i++) {
                DDlogRecord record = DDlogRecord.fromSharedHandle(handle);
                DDlogRecCommand command = new DDlogRecCommand(kind, tableid, record);
                this.commitCallback.accept(command);
            }
        }
    }

    public int getTableId(String table) {
        if (!this.tableId.containsKey(table)) {
            int id = ddlog_get_table_id(table);
            this.tableId.put(table, id);
            return id;
        }
        return this.tableId.get(table);
    }

    // Record DDlog commands to file.
    // Set `filename` to `null` to stop recording.
    // Set `append` to `true` to open the file in append mode.
    public void recordCommands(String filename, boolean append) throws DDlogException, IOException {
        if (this.record_fd != -1) {
            DDlogAPI.ddlog_stop_recording(this.hprog, this.record_fd);
            this.record_fd = -1;
        }
        if (filename == null) {
            return;
        }

        int fd = DDlogAPI.ddlog_record_commands(this.hprog, filename, append);
        this.record_fd = fd;
    }

    public void dumpInputSnapshot(String filename, boolean append) throws DDlogException, IOException {
        DDlogAPI.ddlog_dump_input_snapshot(this.hprog, filename, append);
    }

    public void stop() throws DDlogException {
        /* Close the file handle. */
        if (this.record_fd != -1) {
            DDlogAPI.ddlog_stop_recording(this.hprog, this.record_fd);
            this.record_fd = -1;
        }
        this.ddlog_stop(this.hprog, this.callbackHandle);
    }

    /**
     *  Starts a transaction
     */
    public void transactionStart() throws DDlogException {
        DDlogAPI.ddlog_transaction_start(this.hprog);
    }

    public void transactionCommit() throws DDlogException {
        DDlogAPI.ddlog_transaction_commit(this.hprog);
    }

    public void transactionCommitDumpChanges(Consumer<DDlogCommand<DDlogRecord>> callback)
            throws DDlogException {
        String onDelta = callback == null ? null : "onDelta";
        this.deltaCallback = callback;
        this.ddlog_transaction_commit_dump_changes(this.hprog, onDelta);
    }

    public int clearRelation(int relid) {
        return ddlog_clear_relation(this.hprog, relid);
    }

    // Callback invoked from commit_dump_changes.
    void onDelta(int tableid, long handle, boolean polarity) {
        if (this.deltaCallback != null) {
            DDlogCommand.Kind kind = polarity ? DDlogCommand.Kind.Insert : DDlogCommand.Kind.DeleteVal;
            DDlogRecord record = DDlogRecord.fromSharedHandle(handle);
            DDlogRecCommand command = new DDlogRecCommand(kind, tableid, record);
            this.deltaCallback.accept(command);
        }
    }

    // The FlatBufDescr class and commitDumpChangesToFlatbuf method are not
    // meant to be invoked directly by user code; they are only used by the
    // autogenerated FlatBuffer API code.
    public static class FlatBufDescr {
        public FlatBufDescr() {
            this.buf = null;
            this.size = 0;
            this.offset = 0;
        }
        public void set(ByteBuffer buf, long size, long offset) {
            this.buf = buf;
            this.size = size;
            this.offset = offset;
        }
        public ByteBuffer buf;
        public long size;
        public long offset;
    }

    public void transactionCommitDumpChangesToFlatbuf(FlatBufDescr fb) throws DDlogException {
        this.ddlog_transaction_commit_dump_changes_to_flatbuf(this.hprog, fb);
    }

    public void flatbufFree(FlatBufDescr fb) {
        this.ddlog_flatbuf_free(fb.buf, fb.size, fb.offset);
    }

    public void transactionRollback() throws DDlogException {
        DDlogAPI.ddlog_transaction_rollback(this.hprog);
    }

    public void applyUpdates(DDlogRecCommand[] commands) throws DDlogException {
        long[] handles = new long[commands.length];
        for (int i=0; i < commands.length; i++)
            handles[i] = commands[i].allocate();
        ddlog_apply_updates(this.hprog, handles);
    }

    public void applyUpdatesFromFlatBuf(ByteBuffer buf) throws DDlogException {
        ddlog_apply_updates_from_flatbuf(this.hprog, buf.array(), buf.position());
    }

    /// Callback invoked from dump.
    boolean dumpCallback(long handle) {
        if (this.dumpCallback != null) {
            DDlogRecord record = DDlogRecord.fromSharedHandle(handle);
            this.dumpCallback.accept(record);
        }
        return true;
    }

    /**
     * Dump the data in the specified table.
     * For this to work the DDlogAPI must have been created with a
     * storeData parameter set to true.
     */
    public void dumpTable(String table, Consumer<DDlogRecord> callback) throws DDlogException {
        int id = this.getTableId(table);
        if (id == -1)
            throw new RuntimeException("Unknown table " + table);
        String onDump = callback == null ? null : "dumpCallback";
        this.dumpCallback = callback;
        this.dump_table(this.hprog, id, onDump);
    }

    public String profile() {
        return DDlogAPI.ddlog_profile(this.hprog);
    }

    public void enableCpuProfiling(boolean enable) throws DDlogException {
        DDlogAPI.ddlog_enable_cpu_profiling(this.hprog, enable);
    }

    /*
     * Control DDlog logging behavior (see detailed explanation of the logging
     * API in `lib/log.dl`).
     *
     * `module` - Module id for logging purposes.  Must match module ids
     *    used in the DDlog program.
     * `cb` - Logging callback that takes log message level and the message
     *    itself.  Passing `null` disables logging for the given module.
     * `max_level` - Maximal enabled log level.  Log messages with this module
     *    id and log level above `max_level` will be dropped by DDlog (i.e.,
     *    the callback will not be invoked for  those messages).
     */
    static public synchronized void logSetCallback(int module, ObjIntConsumer<String> cb, int max_level) {
        Long old_cbinfo = logCBInfo.remove(module);
        long new_cbinfo = ddlog_log_replace_callback(module, old_cbinfo == null ? 0 : old_cbinfo, cb, max_level);
        /* Store pointer to CallbackInfo in internal map */
        if (new_cbinfo != 0) {
            logCBInfo.put(module, new_cbinfo);
        }
    }
}
