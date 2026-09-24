package com.maodouchat.explore.policy

/**
 * 探索页的分页尺寸（G328c 从 `ExploreOrchestrator` 的私有 companion 抽出）。
 *
 * 抽它的直接动机是热点棘轮：那次分层重构给 ExploreOrchestrator 加了几行必需的 import，
 * 而棘轮规定「要放宽上限必须先真的删掉代码」。这两条常量是**策略**（一页拉多少），
 * 与编排逻辑无关，独立成对象既让文件净减 3 行，也让分页尺寸有了单一出处。
 */
internal object ExplorePaging {
    const val COMMENTS_PAGE_SIZE = 50
    const val FEED_PAGE_SIZE = 40
}
