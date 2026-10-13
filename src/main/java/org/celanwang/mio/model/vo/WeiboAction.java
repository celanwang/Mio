package org.celanwang.mio.model.vo;

/** 仅在当前微博任务需要授权或重试时，向对应对话提供入口。 */
public record WeiboAction(String type, String message) { }
