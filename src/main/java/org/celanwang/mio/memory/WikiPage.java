package org.celanwang.mio.memory;

/**
 * 一页用户画像 Wiki 页。
 *
 * @param slug       页面唯一标识（文件名主体，创建后不变）
 * @param title      页面标题（展示用）
 * @param scope      作用域：global 或领域名（如 mcd-ordering）
 * @param source     来源：explicit（用户/面板手动）或 inferred（提炼器推断）
 * @param confidence 置信度 0.0-1.0
 * @param updatedAt  更新时间（毫秒）
 * @param content    页面正文（Markdown，可持续改写）
 */
public record WikiPage(String slug, String title, String scope, String source,
                       double confidence, long updatedAt, String content) {
}
