package com.maodouchat.domain.contacts


// 附近的人搜索：显示名、名字、id、个性签名四字段任一含查询词即命中。
// 自 ExploreNearbyScreen 的 filteredPeople 收敛；字段集含 status，与 ExploreSearchPolicy
// 和 ContactMemberCandidates 均不同，不可复用二者。
object NearbySearchPolicy {

    fun matches(person: NearbyPerson, query: String): Boolean =
        person.user.displayName.contains(query, ignoreCase = true) ||
            person.user.name.contains(query, ignoreCase = true) ||
            person.user.id.contains(query, ignoreCase = true) ||
            person.user.status.contains(query, ignoreCase = true)

    fun filter(people: List<NearbyPerson>, query: String): List<NearbyPerson> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return people
        return people.filter { matches(it, trimmed) }
    }
}
