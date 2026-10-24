from app.evidence import verify_quote

TRANSCRIPT = "Alex: Priya, please prepare the test report by Friday.\nPriya: Sure,\n  I will   do it."


def test_exact_quote_verified_with_offsets():
    quote = "please prepare the test report by Friday"
    r = verify_quote(TRANSCRIPT, quote)
    assert r.verified and r.match == "exact"
    assert TRANSCRIPT[r.start:r.end] == quote


def test_quote_missing_from_transcript_is_unverified():
    r = verify_quote(TRANSCRIPT, "Priya will deploy to production on Monday")
    assert not r.verified and r.start is None and r.match is None


def test_empty_and_whitespace_quotes_are_unverified():
    assert not verify_quote(TRANSCRIPT, "").verified
    assert not verify_quote(TRANSCRIPT, "   \n").verified
    assert not verify_quote(TRANSCRIPT, None).verified


def test_whitespace_normalized_match_maps_back_to_original_offsets():
    r = verify_quote(TRANSCRIPT, "Sure, I will do it.")
    assert r.verified and r.match == "whitespace_normalized"
    assert TRANSCRIPT[r.start:r.end] == "Sure,\n  I will   do it."


def test_comparison_is_case_sensitive():
    assert not verify_quote(TRANSCRIPT, "PLEASE PREPARE THE TEST REPORT").verified
