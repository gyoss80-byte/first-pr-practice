import unittest

from stringutils import is_palindrome, reverse_words


class TestIsPalindrome(unittest.TestCase):
    def test_simple_palindrome(self):
        self.assertTrue(is_palindrome("racecar"))

    def test_non_palindrome(self):
        self.assertFalse(is_palindrome("hello"))

    def test_ignores_case_and_spaces(self):
        self.assertTrue(is_palindrome("Nurses Run"))


class TestReverseWords(unittest.TestCase):
    def test_reverses_word_order(self):
        self.assertEqual(reverse_words("hello world"), "world hello")

    def test_single_word_unchanged(self):
        self.assertEqual(reverse_words("hello"), "hello")


if __name__ == "__main__":
    unittest.main()
