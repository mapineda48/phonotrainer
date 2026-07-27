"""MEJORA 0: saneamiento del inventario de fonos (vocabulario multilingüe → inglés)."""

import pytest

from phonotrainer.canonical import dict_pronunciation
from phonotrainer.ipa_maps import ENGLISH_INVENTORY, normalize_espeak


def test_la_forma_de_diccionario_conserva_el_acento():
    """El acento explica la reducción: /bˈɛtɚ/ enseña que el flap cae en la
    sílaba átona. Las reglas siguen mirando el ARPAbet, no esta cadena."""
    entrada = dict_pronunciation("better")
    assert entrada["ipa"] == "bˈɛtɚ"
    assert entrada["arpabet"] == ["B", "EH1", "T", "ER0"]
    # y AH0 es schwa: la sílaba átona se ve como tal
    assert dict_pronunciation("tonight")["ipa"] == "tənˈaɪt"
    assert dict_pronunciation("the")["ipa"] == "ðə"


def test_tokens_ingleses_pasan_intactos():
    for tok in ["ɾ", "ʔ", "oʊ", "aɪ", "ə", "ɚ", "θ", "ð", "ŋ", "iː", "n̩", "əl", "ʊɹ", "ɔːɹ"]:
        assert normalize_espeak(tok) == tok, tok


def test_digitos_de_tono_mandarin():
    assert normalize_espeak("ai5") == "aɪ"
    assert normalize_espeak("ɑu5") == "aʊ"
    assert normalize_espeak("ei2") == "eɪ"
    assert normalize_espeak("ou5") == "oʊ"
    assert normalize_espeak("u5") == "u"
    assert normalize_espeak("a5") == "æ"


def test_aspiradas_a_simples():
    # ambas grafías del vocab: con modificador (kʰ) y con h plana (kh)
    assert normalize_espeak("kʰ") == "k"
    assert normalize_espeak("kh") == "k"
    assert normalize_espeak("th") == "t"
    assert normalize_espeak("ph") == "p"
    assert normalize_espeak("tʰ") == "t"
    assert normalize_espeak("pʰ") == "p"
    assert normalize_espeak("tʃʰ") == "tʃ"


def test_simbolos_fuera_de_inventario():
    assert normalize_espeak("ᵻ") == "ɪ"
    assert normalize_espeak("dZ") == "dʒ"   # SAMPA crudo
    assert normalize_espeak("tS") == "tʃ"
    assert normalize_espeak("S") == "ʃ"
    assert normalize_espeak("N") == "ŋ"
    assert normalize_espeak("r") == "ɹ"
    assert normalize_espeak("ɫ") == "l"
    assert normalize_espeak("t̪") == "t"
    assert normalize_espeak("nʲ") == "n"    # palatalizadas → simples


def test_monoptongos_crudos_no_se_diptongan():
    # un [o] o [e] escueto es una realización monoptongada: NO mapear al diptongo
    assert normalize_espeak("o") == "ɔ"
    assert normalize_espeak("e") == "ɛ"
    assert normalize_espeak("a") == "æ"


def test_vecino_mas_cercano_para_exoticos():
    # sin entrada explícita: caen al vecino panphon dentro del inventario
    for tok in ["ʂ", "ɖ", "œ", "ɯ", "χ", "ɴ"]:
        res = normalize_espeak(tok)
        assert res in ENGLISH_INVENTORY, f"{tok} → {res}"


@pytest.mark.slow
def test_cobertura_total_del_vocabulario():
    """TODO el vocab del tokenizer debe mapear a inventario inglés (o descartarse)."""
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    specials = set(tok.all_special_tokens)
    unmapped = []
    dropped = []
    for t in tok.get_vocab():
        if t in specials:
            continue
        res = normalize_espeak(t)
        if res is None:
            dropped.append(t)
        elif res not in ENGLISH_INVENTORY:
            unmapped.append((t, res))
    assert not unmapped, f"tokens que no mapean a inventario: {unmapped}"
    # los descartados deben ser residuales (basura tipo '??', 'ʲ' suelto, '1')
    assert len(dropped) < 12, f"demasiados tokens descartados: {dropped}"
