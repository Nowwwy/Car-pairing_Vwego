package ai.carpairing.app

internal object RouteVoiceChoice {
    private val numbers = mapOf(
        "일" to 1, "첫" to 1, "이" to 2, "두" to 2, "삼" to 3, "세" to 3, "사" to 4, "네" to 4,
        "오" to 5, "다섯" to 5, "육" to 6, "여섯" to 6, "칠" to 7, "일곱" to 7, "팔" to 8,
        "여덟" to 8, "구" to 9, "아홉" to 9, "십" to 10, "열" to 10,
    )
    private val selector = Regex("^(?:응|네|그럼|그러면|좋아)?(?:추천)?" +
        "([0-9]{1,3}|${numbers.keys.joinToString("|")})(?:번(?:째)?|번째)?" +
        "(?:후보)?(?:으로|로|을|를)?(?:가|가자|가죠|가줘|가주세요|갑시다|선택|선택해죠|선택해줘|" +
        "선택해주세요|선택할게|골라줘|안내|안내해줘|안내해주세요|해줘|해주세요)?$")
    private val numberedReference = Regex("[0-9]+(?:번|번째)|(?:${numbers.keys.joinToString("|")})(?:번|번째)")
    private val firstChoiceHomophone = Regex("^일본(?:으로|을)(?:가줘|가죠|선택해줘|선택해죠)$")
    private val confirm = setOf("응", "네", "예", "응확인", "네확인", "확인", "확인해줘", "응확인해줘", "네확인해줘",
        "확인해주세요", "네확인해주세요", "좋아", "응좋아", "네좋아요",
        "그렇게해줘", "응그렇게해줘", "네그렇게해주세요", "진행해줘", "응진행해줘", "진행해주세요",
        "응변경해줘", "변경해줘", "안내해줘", "응안내해줘", "네안내해주세요", "추가해줘", "응추가해줘",
        "가자", "응가자", "가죠", "가줘", "응가줘", "네가주세요", "확인해죠", "응확인해죠")
    private val cancel = setOf("아니", "아니요", "됐어", "취소", "취소해줘", "취소해주세요", "유지해줘", "그대로가",
        "그대로가자", "지금경로유지", "현재경로유지", "현재경로를유지해줘", "아니현재경로유지",
        "가지마", "가지마세요", "바꾸지마", "변경하지마", "추가하지마", "아니취소", "아니요취소", "아니취소해줘")

    fun number(text: String): Int? {
        val token = selector.matchEntire(normalize(text))?.groupValues?.get(1) ?: return null
        return token.toIntOrNull() ?: numbers[token]
    }

    fun candidateNumber(text: String): Int? = number(text) ?: 1.takeIf { firstChoiceHomophone.matches(normalize(text)) }
    fun mentionsNumber(text: String) = numberedReference.containsMatchIn(normalize(text))
    fun confirms(text: String) = !text.contains('?') && !text.contains('？') && normalize(text) in confirm
    fun cancels(text: String) = normalize(text) in cancel

    private fun normalize(text: String) = text.filter(Char::isLetterOrDigit)
}
