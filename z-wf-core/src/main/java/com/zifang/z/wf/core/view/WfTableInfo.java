package com.zifang.z.wf.core.view;

import java.io.Serializable;

/**
 * 一项存储实体的自省结果 —— 名字、它到底是表还是集合、有多少行。
 *
 * <p>对应 Camunda 的 {@code getTableNames()} + {@code getTableCount()}，
 * 但把两件事合到一条记录里，并且<b>显式标出 {@link #kind}</b>。
 *
 * <p><b>为什么必须标 kind。</b>内存实现下没有表，它持有的是 Map。
 * 若不标出来，运维在内存模式下看到 {@code ZWF_TASK} 会理所当然地以为
 * 库里真有这张表，去数据库一查没有，于是先去怀疑数据库 ——
 * 而真相是"这个引擎压根没连库"。一句话就能省掉一段错误排查。
 *
 * @author zifang
 */
public class WfTableInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 存在关系库里的一张表。 */
    public static final String KIND_TABLE = "table";

    /** 进程内的一个集合（内存实现）。 */
    public static final String KIND_COLLECTION = "collection";

    private final String name;

    private final String kind;

    private final long rowCount;

    public WfTableInfo(String name, String kind, long rowCount) {
        this.name = name;
        this.kind = kind;
        this.rowCount = rowCount;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public long getRowCount() {
        return rowCount;
    }

    @Override
    public String toString() {
        return name + "(" + kind + ")=" + rowCount;
    }
}