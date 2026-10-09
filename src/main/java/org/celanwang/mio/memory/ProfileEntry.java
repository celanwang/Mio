package org.celanwang.mio.memory;

/**
 * 一条用户画像条目。
 *
 * @param key        偏好名，如「口味偏好」
 * @param value      偏好内容，如「不吃辣」
 * @param scope      作用域：global 或领域名（如 mcd-ordering）
 * @param source     来源：explicit（用户明确表达/手动设置）或 inferred（系统推断）
 * @param confidence 置信度 0.0-1.0
 * @param updatedAt  更新时间（毫秒）
 * @param validTo    失效时间（毫秒）；null 表示当前有效（冲突更新时旧条目软失效而非删除）
 */
public record ProfileEntry(String key, String value, String scope, String source,
                           double confidence, long updatedAt, Long validTo) {

    public boolean active() {
        return validTo == null;
    }
}
