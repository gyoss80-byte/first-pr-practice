def is_palindrome(text):
    cleaned = text.replace(" ", "").lower()
    return cleaned == cleaned[::-1]


def reverse_words(text):
    return " ".join(text.split()[::-1])
