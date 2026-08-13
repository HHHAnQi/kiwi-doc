package com.xxx.ragdoc.application.chat.router;

/** 在线请求的一级执行路由。具体采用哪种 RAG/Agent pipeline 由二级策略决定。 */
public enum OnlineRoute {
    CHAT,
    RETRIEVE,
    TOOL,
    REFUSE
}
