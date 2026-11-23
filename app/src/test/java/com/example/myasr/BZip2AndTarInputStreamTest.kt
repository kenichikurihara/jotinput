package com.example.myasr

import com.example.myasr.context.provisioning.infrastructure.BZip2InputStream
import com.example.myasr.context.provisioning.infrastructure.TarInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class BZip2AndTarInputStreamTest {

    companion object {
        private const val EMPTY_BZ2 =
            "QlpoORdyRThQkAAAAAA="
        private const val JP_BZ2 =
            "QlpoOTFBWSZTWaWCJR0AAKJf+QAQYAAQABAwABAAIcYAP/KAjR6WACQLQDAAuAxo0aAZMhoxGmgaYGNGjQDJkNGI00DTARUh" +
            "Bo0aA0AaZGmT0kidkqTmlKZklSlM6TJBJ0oTSlCak1psTYmhMFA3JAEgn5IJxEkSAkiWpBJU6pETBK0qSVL0imCZ0rT2mIly" +
            "TJYJOliSJantPSQEtS5MRL0/JFKUikUvSZICYp/F3JFOFCQpYIlHQA=="
        private const val RLE_BZ2 =
            "QlpoOTFBWSZTWS5VtSIAAAjUAeAAACA+AACAAAigACK/VPRqNqCAaaaJEafV3PUyMrhkRhVoRfjRbdEDmfF3JFOFCQLlW1Ig"
        private const val RAND_RAW =
            "oxwGvUY+OSO8Gq295IsWl2wIBxc3O4GaBo8yt6azi2s4cpZHz94Bws4osmxXRyc39cNWGhdhGFvYWJpDzgu6dYkf+exgFI1L" +
            "1KCe4txckzG0EQupOsVK/BTaO90ZYUd0otVdKV5aNatEs++upRKboiuIuj4pdmFF/eyjsI44r1PXxMYOOtIIzlBmRBA26fGR" +
            "4LdQNqd/ZeLqpHUkQyM/vo+JQ7+VbeWVZlw4//8jgn4XwQzcHCegKMqubJgQYmGY/3eHQPiN3PECrrgdruKJwETEpFccS28o" +
            "dAD0uOC4Q/iAwy2B6RveoEzXo4GbMidfwymK9Mfsh+sAmVJ9BBztXOD81M5OPQ494JHyFBW7fNAR+sKIxCAgqHnyjCpDh9+b" +
            "bPY27YrBurAztk9m/qumX3DmhHMePzkQVgWWjTqWOAEStaEPOhHnCNxUEoM8R6t8Noohue/hkpN5Psh5zmgwGBioblpsaXfd" +
            "ug2sp/ulGQ9nulbM3Bs/MTCJciNsLkd2P9/sE3HO3NuMGQym/4rWA/gX7cDZPCpofHs23WbnDyphAPxjQ+3IyHRJbLL1u/7I" +
            "jqm3fCcwSzf3DpS8ig+/UA4MlXqA69qHKA71ghTZLxGYEazcPGce8eORP5SYCp4Ua6iVkIVQ70I0q7dQPUNlIaulTHVQ7cDv" +
            "EgJ1n/+Q/xkSiTaBQyHuWeER4T5eSChw1Yu0TZz7/M6nhwKq0Y1M7qka8OAiQx3jG76NJ0VImjW3VzSvotpDgX1A5+jYDRei" +
            "bNRGCwBVxSGj+kMpvXGNtG2PAhwT8eKw5yaLCdVelY0lbiAKTl3m7sv43ArmWzWuP6oaWseP4t9o+Z6/J+zuPN0p+czPLeFp" +
            "Bi287FXI7mnNq928zz9EKMmzG2HfCdt4ODPR63VZTtLL3zo5BqgxZlRH3RH3xUdZpIJmrfvXiVTwBx3g+EItlPb7QwkbmG9Y" +
            "uslQb5v7gh1i5pMwQQu1bwCF7M6Jr7jwvbyrMl1uEfKq61SfUKnZH7jmTIFPqmhTZ7JLjSAxa6rwYa2/5yydkU1njNUATUk1" +
            "bsmUm6dSd3FxrDaCecvm9cu8K6gVSIOpop5VF9HzwDysTznOMiUGCz77eZzZxBJ0auKhkzG3smJ+Zj4lp7AB5MDcxeIbx2w4" +
            "Lc31soR2DI4/6tkfdCLNdqqH/I+YUfPB5HGc0LjkgW3U6Ixy5Si+3HlzQsA/16NGxMeFfKA9RnATtkk8RVVR5IoUIyY7YrEn" +
            "tDYQamhUindqDzTVa2PnxZXysgXb4cOTYXoB8VpMwGPa5PTVa4m/vIvMmuU4fDhFb3wHY1arrcxnuSrXd+sg+5+IBuhkl5Cp" +
            "BhWkbSLddi4MQmFTNnRTVg=="
        private const val RAND_BZ2_1 =
            "QlpoMTFBWSZTWRr/b6QAAUJ////////////////////////+////////////9///////wAKtx1t3WLtYgTE9TGgjJpgATRga" +
            "TDTUybQJpk2k00wj0ATAAJ6TaaBMBAME8hGTTTeoCYTT0CYmA0noJgBG9SYDUaZqaZPQanpM0DoBDCep6mjTAJpmoMmJgBoI" +
            "ZB+o0RppgAGgAJgACMR+oTTaZNE8o2k9IyY0TBNHoeoBMTBHoEyMT0I9BMT0NIGaTIExNMNBMmDSYmjTBoGgBMBMTTIwm0mB" +
            "oAAACaMNNGhGGpgAmDQEwam0JgANDTQAAAAAmTCMADoAABPRMNANNANA9QTAAAAaAaCYAmEwaBNMAAAAJpiMTJgAjAEBgaNR" +
            "gmTTRg1MDSaYABlBRn7HPgnQr48gkSpYHxPVkHIxNE0rA9NLeePBKufqKMFLpvKqHdwe1LiGLC0L6Gxmzm+BlF1hSKdfOZto" +
            "1oI9lxkNjS48oOWWqx/ZcHyeSykKTrGgmYGEamSjjeh9Xm79Gfccr+Lnr6oMdBw7ix3UlpHg60AjDZ1+8CvgEm/ZXnVsUcq4" +
            "PJbByK4CrNzMltBIsLlSkZIp6H7fqcU5cIgZaF+v4U5PjqfUzFA0J/UvRe69wCRe5FH6asQARgjf7fiQki4wVzWvuvdGvlyr" +
            "6IAr50PTTBLb7nf0KPkALwRQUC9NpZW8cl7bj/I57kuOzzYJ2sFWLMDpfAKFavo4n1bxDKoeZsj5CaKwZywL2JwiJLMnTwox" +
            "PWRaOxuTRMbOlxJNqi0ApwurRC7v64w2IKQ0DarM1yiVIiu0sS532W4F4eF2gRKO1/P+aT2DdY+N4LaEJRDMp1WGCUTpAO/q" +
            "CbIF0GCAP1Co9pJUCIKNQPRciW54Hkm37fKu/MKLhIbOtHH0+vHvxYpiDc5AxWRo0z6SMw6OK9td8XWhcoGVFLuenAWKoGpu" +
            "RpShrlvPdmaMkA9OyhSYBP6axJ3FqsxeFEuMMan27shU6s4eZm1gfV3SvxAvtwFYggvdtf1aZf+MC3qd7CGpjg3v70MQToaK" +
            "4gaYWOcVU/oMFDiXSg2es2QyddRcKFXuBgjQdQpGEC3yY3lji6/SKJ1fhfwOjX0SK8JsOTivB0JyRuELXy4D7vdsOS+DNgkz" +
            "uQDHsEjY5chiDhBLNntytAuWUnSItA4+q8L6eptbYJlj8YwW5dk2NPZzrIb4sRQ7814Jz2AOzX54XXtge1OUvxIV5h0DPJrN" +
            "t9wIjx5INJroh6e6AKAbPXlQEQUxMUuSF1NgOgUKDo55P/4k2PFVf3ZwgBIXD3MXje2gD0kyTnRQ9LklcpPOL7aGKwCt96s3" +
            "0+3FclOZ7lDZUDe1SrSkK9QbPeLsPjUmEzL2NIhYkV6SRkYzqeDcgBwnDem9RQ+02fK8ICWtDKTMFFDpwVtkbnlK/IYlmxNb" +
            "Aw2c1KvdinZAY8whyO/ZCtepcAwVXwBucBCUbkJdnYR217Ckn2UFGAKgcJ5XMF/1HFmuuzYxY1OjC2SlHgj4ZaKyUtmoz41S" +
            "jbIOPiVK+BIL3ui/w0gv4fUURhnP0QSxMarxo0YGz7N5quQASQRZ2wDxg126rEwtVks/zGkyODp3YHZXquRUA/QERiHwNezt" +
            "4/uBnip+8VUC/EIxzvxQTG3bXnKlT3FkwBtM3fDY6cvfOAH/Sty9tGGz5ue8NMniF/RoKYidFx93cWErEcSOweBB/Iopdg6l" +
            "lo23GLuSKcKEgNf7fSA="
        private const val RAND_BZ2_9 =
            "QlpoOTFBWSZTWRr/b6QAAUJ////////////////////////+////////////9///////wAKtx1t3WLtYgTE9TGgjJpgATRga" +
            "TDTUybQJpk2k00wj0ATAAJ6TaaBMBAME8hGTTTeoCYTT0CYmA0noJgBG9SYDUaZqaZPQanpM0DoBDCep6mjTAJpmoMmJgBoI" +
            "ZB+o0RppgAGgAJgACMR+oTTaZNE8o2k9IyY0TBNHoeoBMTBHoEyMT0I9BMT0NIGaTIExNMNBMmDSYmjTBoGgBMBMTTIwm0mB" +
            "oAAACaMNNGhGGpgAmDQEwam0JgANDTQAAAAAmTCMADoAABPRMNANNANA9QTAAAAaAaCYAmEwaBNMAAAAJpiMTJgAjAEBgaNR" +
            "gmTTRg1MDSaYABlBRn7HPgnQr48gkSpYHxPVkHIxNE0rA9NLeePBKufqKMFLpvKqHdwe1LiGLC0L6Gxmzm+BlF1hSKdfOZto" +
            "1oI9lxkNjS48oOWWqx/ZcHyeSykKTrGgmYGEamSjjeh9Xm79Gfccr+Lnr6oMdBw7ix3UlpHg60AjDZ1+8CvgEm/ZXnVsUcq4" +
            "PJbByK4CrNzMltBIsLlSkZIp6H7fqcU5cIgZaF+v4U5PjqfUzFA0J/UvRe69wCRe5FH6asQARgjf7fiQki4wVzWvuvdGvlyr" +
            "6IAr50PTTBLb7nf0KPkALwRQUC9NpZW8cl7bj/I57kuOzzYJ2sFWLMDpfAKFavo4n1bxDKoeZsj5CaKwZywL2JwiJLMnTwox" +
            "PWRaOxuTRMbOlxJNqi0ApwurRC7v64w2IKQ0DarM1yiVIiu0sS532W4F4eF2gRKO1/P+aT2DdY+N4LaEJRDMp1WGCUTpAO/q" +
            "CbIF0GCAP1Co9pJUCIKNQPRciW54Hkm37fKu/MKLhIbOtHH0+vHvxYpiDc5AxWRo0z6SMw6OK9td8XWhcoGVFLuenAWKoGpu" +
            "RpShrlvPdmaMkA9OyhSYBP6axJ3FqsxeFEuMMan27shU6s4eZm1gfV3SvxAvtwFYggvdtf1aZf+MC3qd7CGpjg3v70MQToaK" +
            "4gaYWOcVU/oMFDiXSg2es2QyddRcKFXuBgjQdQpGEC3yY3lji6/SKJ1fhfwOjX0SK8JsOTivB0JyRuELXy4D7vdsOS+DNgkz" +
            "uQDHsEjY5chiDhBLNntytAuWUnSItA4+q8L6eptbYJlj8YwW5dk2NPZzrIb4sRQ7814Jz2AOzX54XXtge1OUvxIV5h0DPJrN" +
            "t9wIjx5INJroh6e6AKAbPXlQEQUxMUuSF1NgOgUKDo55P/4k2PFVf3ZwgBIXD3MXje2gD0kyTnRQ9LklcpPOL7aGKwCt96s3" +
            "0+3FclOZ7lDZUDe1SrSkK9QbPeLsPjUmEzL2NIhYkV6SRkYzqeDcgBwnDem9RQ+02fK8ICWtDKTMFFDpwVtkbnlK/IYlmxNb" +
            "Aw2c1KvdinZAY8whyO/ZCtepcAwVXwBucBCUbkJdnYR217Ckn2UFGAKgcJ5XMF/1HFmuuzYxY1OjC2SlHgj4ZaKyUtmoz41S" +
            "jbIOPiVK+BIL3ui/w0gv4fUURhnP0QSxMarxo0YGz7N5quQASQRZ2wDxg126rEwtVks/zGkyODp3YHZXquRUA/QERiHwNezt" +
            "4/uBnip+8VUC/EIxzvxQTG3bXnKlT3FkwBtM3fDY6cvfOAH/Sty9tGGz5ue8NMniF/RoKYidFx93cWErEcSOweBB/Iopdg6l" +
            "lo23GLuSKcKEgNf7fSA="
        private const val MULTIBLOCK_MOZC_BZ2_1 =
            "QlpoMTFBWSZTWaqr59sAr8VIcWAwf+AwAgxBgBAKQGBCvAAAAAAAAAAAAAAAAAAHvgAAAAAADQAAAAAABQAAAbSlKUpSACUp" +
            "SlKUpSACUpSlVSqgAqpVVVKqACqlVVUqoANVSqqlABVUqqpQAVVKqqUAFVSqqlABVUqqpYFAAAGTIKAAAMmQUAAAZMgx+VP1" +
            "VI/3+qlSqAAMMqf+qqn+qUj//VSUA0MCKn/6VVT/VSjf/6qqaAAH5/JP55575b/eO/v+X+f73ve973vegAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/QB0AAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6AOgAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAHPfPPfPPfPfPff0A70AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAA/QB0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAA6AOgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP75575/f7/f7/f7/QAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAB755755757576AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHnnvgAbbbbbbbfE" +
            "tkktsnWySW2Tzu7bbbbbbbbbb3d873t8+d3d8+fO6v2/gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD6fQ+ve8AABdQAAF1AA" +
            "AXUAABdQAAF1AAAXUAABdQAAF1AAAXUAAB7x7veAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAA9493vAAAXUAA" +
            "BdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAe8e73gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAAPePd7wAA" +
            "F1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAHvHu94AAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAD3j3" +
            "e8AABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAAB7x7veAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAA" +
            "A9493vAAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAbf3+/39/f39/QAD7774AAA29Vd9vsAABdQAAF1AAAXUAA" +
            "BdQAAF1AAAXUAABdQAAF1AAAXUAABe9Vd7wAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF71V3vAAAXUAABdQA" +
            "AF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXvVXe8AABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABe9Vd7wAAF1A" +
            "AAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF71V3vAAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXvVXe8" +
            "AABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABe9Vd7wAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF" +
            "71V3vAAAXUAABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAAXvVXe8AABdQAAF1AAAXUAABdQAAF1AAAXUAABdQAAF1AAA" +
            "XUAEkkkm38/n7+/v7+/oAB9998AAbbbe96q77fYAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC96q73gAALqAA" +
            "AuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAAL3qrveAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAvequ94A" +
            "AC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC96q73gAALqAAAuoAAC6gAALqAAAuoAAC6gAALqAAAuoAAC6gAAve" +
            "qvnfOqpZYBZYBZYBJYfT6fPz8/PzttsAPvvvgAADbW2++e+W222WAWWAWWAWWAWWAWWAWWAWWASWHdtttttsAffffAAAG2tt" +
            "9898ttt9lgFlgFlgFlgFlgFlgFlgFlgElh3bbbbbbYB9998AAAba233z3y2233ywCywCywCywCywCywCywCywCSw7ttttttt" +
            "gPvvvgAADbW2++e+W22++ewCywCywCywCywCywCywCywCSw7tttttttsD7774AAA21tvvnvlttvvnvgFlgFlgFlgFlgFlgFl" +
            "gFlgElh3bbbbbbbbB9998AAAba233z3y2233z3ygssAssAssAssAssAssAssAksO7bbbbbbbbD7774AAA21tvvnvlttvvnvl" +
            "oWWAWWAWWAWWAWWAWWAWWASWJ+3nx69e/fv3797bbbYAAAAAAAAAAAAAABznOc5znAAAAAAAAAAAAAAAAAAAAAB0AdAAAAAA" +
            "AAAAAAAAAADnOc5znOAAAAAAAAAAAAAAAAAAAAAAAAABttgAADbf6ee++eeee+++efffX3ywCywCywCywCywCywCywCywCyw" +
            "CSxPPHr1773vXOc5znOcDbbAc5znOc5wNtbb5LE8eeeWWAWWAWWAWWAWWAWWAWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWA" +
            "WWAWWAWWAWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWW" +
            "AWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWAW" +
            "WHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWAWWHdsAABttgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWAWWHdsAABt" +
            "tgAADbW22WAWWAWWAWWAWWAWWAWWAWWAWWASWeT5+Hr873ve9AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc5znOc5wAADo" +
            "A6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5znOc5zgAAAAAADbbAAAG2tqywCywCywCywCywCywCywCywCywCyw7sAAA2" +
            "2wAABtrassAssAssAssAssAssAssAssAssAks8nhsAADnPvmwAAOc++WllgElnk8eeeWWeAssAssAssAssAssAssAssOwAAB" +
            "ttgAADbUWWAWWAWWAWWAWWAWWAWWAWWAWWAWWHYAAA22wAABtqLLALLALLALLALLALLALLALLALLALZDsAAAbbYAAA21AAAA" +
            "AAAALbbbbsAAAbbYAA2227uAAAAAABbbbbbbbbbdgAADbbBtttttttt3cAAAAAAAAAB3bbbbbbbbbZttgAADbW2222222220" +
            "AAAAAAd22222ABttgAADbW22220AAAAAAAPZ6/O973vQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAdAHQAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAANtsAAAbagAAAAAAAFtttt2AAANtsAAbbbd3AAAAAAAtttttttttuwAABttg222" +
            "222227uAAAAAAAAAA7ttttttttts22wAABtrbbbbbbbbbaAAAAAAO7bbbbAA22wAABtrbbbbaAAAAAAAA7AAAG22AAANtQAA" +
            "AAAAAC22227AAAG22AANttu7gAAAAAAW222222223YAAA22wbbbbbbbbd3AAAAAAAAAAd2222222222bbYAAA21ttttttttt" +
            "tAAAAAAHdttttgAbbYAAA21tttttAAAAAAAD2evzve970AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHQB0AAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADbbAAAG2oAAAAAAABbbbbdgAADbbAAG223dwAAAAAALbbbbbbbbbsAAAbbYNt" +
            "ttttttu7gAAAAAAAAAO7bbbbbbbbbNtsAAAba2222222222gAAAAADu2222wANtsAAAba22222gAAAAAAAOwAABttgAADbUA" +
            "AAAAAAAttttuwAABttgADbbbu4AAAAAAFttttttttt2AAANtsG22222223dwAAAAAAAAAHdtttttttttm22AAANtbbbbbbbb" +
            "bbQAAAAAB3bbbbYAG22AAANtbbbbbQAAAAAAPPPfnx69e+973oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOgDoAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAG22AAANpP3353d3z587qAAAAAA+v1/O973oAA22wAABtIAAAAAAe97bb" +
            "bbAA22wAABtJJJJJIAAAAHve222222222zbbAAAG0kkkkkkkkkkkkkkkkkkkm2AAANtsG22222223vAAAAAEkkkm2AAANtsA" +
            "Abbbe8AAAAAAHvYAAA22wAABtIAAAAAAe97bbbbAA22wAABtJJJJJIAAAAHve222222222zbbAAAG0kkkkkkkkkkkkkkkkkk" +
            "km2AAANtsG22222223vAAAAAEkkn7Pz8/O973vQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAdAHQAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAANtsAAbbbe8AAAAAAHvYAAA22wAABtIAAAAAAe97bbbbAA22wAABtJJJJJIAAAAHve22" +
            "2222222zbbAAAG0kkkkkkkkkkkkkkkkkkkm2AAANtsG22222223vAAAAAEkkkm2AAANtsAAbbbe8AAAAAAHvYAAA22wAABtI" +
            "AAAAAAe97bbbbAA22wAABtJJJJJIAAAAHve222222222zbbAAAG0kkkkkkkkkkkkkkkkkkkm2AAANtsG22222223vAAAAAEk" +
            "knZ+fn53ve96AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADoA6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AABttgADbbb3gAAAAAA97AAAG22AAANpAAAAAAD3vbbbbYAG22AAANpJJJJJAAAAA972222222222bbYAAA2kkkkkkkkkkkk" +
            "kkkkkkkk2wAABttg2222222294AAAAAkkkk2wAABttgADbbb3gAAAAAA97AAAG22AAANpAAAAAAD3vbbbbYAG22AAANpJJJJ" +
            "JAAAAA972222222222bbYAAA2kkkkkkkkkkkkkkkkkkkk2wAABttg2222222294AAAAAkkk7Pz8/O973vQAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAdAHQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5znOc5zgAAbbYAAbbb3gAAAAAA97" +
            "wAABttgAADaSAAAAAAPe9ttttsAG22AAANpJJJJJIAAAAPe9ttttttttttttgAADaSSSSSSSSSSSSSSSSSSSTbAAAG22Bttt" +
            "ttttveAAAAAEkkk2wAABttgABttveAAAAAAD3vAAAG22AAANpIAAAAAA9722222wAbbYAAA2kkkkkkgAAAA9722222222222" +
            "22AAANpJJJJJJJJJJJJJJJJJJJNsAAAbbYG222222294AAAAASST87f5/P53veugAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAB3nOc5znO9AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADnOc5znOAABttgAA2294AAAAAAPe9gAAG22AAANpJ" +
            "AAAAAAPe9tttttgBttgAADaSSSSSSQAAAA9722222222223ttgAADaSSSSSSSSSSSSSSSSSSSTbAAAG22A222222294AAAAA" +
            "JJJNsAAAbbYAANtveAAAAAAD3vYAABttgAADaSQAAAAAD3vbbbbbYAbbYAAA2kkkkkkkAAAAPe9tttttttttt7bYAAB99JJJ" +
            "JJJJJJJJJJJJJJJIt2wAAD7774A222222+n0AAAAAA88+1kslllv3tkktsn1tkktsn1tkktsn+2ySW2T72ySW2RbJJbZPxbJ" +
            "JbZPtbJJbZPtZP48sltlsn4tkktsn/WySW2T8WySW2T8mKCskyms+daf8gAH0mQ4oBg/8BgBBiDACAUgMBqeAAAAAAAAAAAA" +
            "AAB9IAAAoAAAAAAKAAAVCgBVKFKABSipVKQBUqlSpVKQBUqlRSqAFUpVKAFUpVKAFUpVKAFUpVKAFUpVIFAANAyZBQADQMmQ" +
            "UAA0DJkET9Sqn+qSn/7/VVFSAAFAANAyZA0/UpSlH+/1VVP1QAYn7/lX8fv+Xvz8fPv8AAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAHwAfAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAD4APgAAAAAAAc5mc5zOc5l/Fvz58+fPnz4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAfQB8AAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPgA+AAAAAAAHve973v" +
            "eAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAzOZznM5zmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAO973oAA/WczM5nOc53mZn" +
            "M5znAfn7AAU5knTu7rQAAAAALtgAAAAAKqqqqqqqqqqqqoAAADbbbbbbbYAAA/1/z3j3gAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAPePeAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9494AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3j3gAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAPePeAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACqqvv379+/fv379AAAAAo/PwAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAD3j3gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPePeAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9494AAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAD3j3gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPePeAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAA9494AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3j3gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPePeAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAA9494AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD8/Pz8/Pz8/Pz82AAAACq+/aqqqqoAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAA9494AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3j3gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPePe" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9494AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3qqqqqqvv379+/fv0AAADbbbYA" +
            "AAAAANgAAAAACqqqqqqqqqqqqqAAAAAACqqqqqqqqqqqqqAAAAAACgAAAAADbbbbbYAAAAKqqqqqqqqqqqqrve96AAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADve96AAAANtttj+86SdO7utAAAAKqqr58+fPnz5" +
            "8AAAAAAAAAAAA22222AAAAAqqqqqqqqqqqqqgAAAANttttgAAAAAAAAAAAACqqqqqqqqqqqqqAAAAAACqqqqqqqqqqqqqAAA" +
            "AAAAAAAAAANttttgAAAAKqqqqqqqqqqqqoAAAADbbbbYAAAAAAAAAAAAAqqqqqqqqqqqqqu973oAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAO973oAAAAAKqqqqqqqqqqqqoAAAAAAAAAAAAA22222AAAAAqqqqq" +
            "qqqqqqqqgAAAANttttgAAAAAAAAAAAACqqqqqqqqqqqqq7gPwAAAAAKruZzN2tzOZu1uZzNpZOlpZOlpZOlpZOlpZOlpZnM3" +
            "a3M5gPwAAAAA2D5J0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ022362AAAAAKruZzN2tzOZaWTpaWTpaWTpaWTpaWTpaX" +
            "M5m7W5nM3a3M5gPwAAANtttttg+SdLSydLSydLSydLSydLSydLSydLSydLSydLSycwH4AAAAAFV3M5LSydLSydLSydLSydLS" +
            "ydLSydLSydLSydLSydLHz575tttttgAAAAAAAAAAHve973veAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3ve973vAAAAAAAAA" +
            "AAAAAAAAA73vegAAAAAqu5nM3a3M5m7W5nM2lk6Wlk6Wlk6Wlk6Wlk6WlmczdrczmA/AAAAADYPknS0snS0snS0snS0snS0s" +
            "nS0snS0snS0snS0snTbbfrYAAAAAqu5nM3a3M5lpZOlpZOlpZOlpZOlpZOlpczmbtbmczdrczmbgP2Pe973veAG+/fv379+/" +
            "fuwfJOlpZOlpZOlllk6Wlk6Wlk6Wlk6Wlk6Wlk6Wlk5gPwAAAAAKruZyWlk6Wlk6Wlk6Wlk6Wlk6Wm5nM3a3M5m7W5nM3a3M" +
            "5gPwAAAAAKruZzN2tzOZu1uZzNpZOlpZOlpZOlpZOlpZOlpZnM3a3M5gPwAAAAA2D5J0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0" +
            "tLJ0tLJ022362AAAAAKruZzN2tzOZaWTpaWTpaWTpaWTpaWTpaXM5m7W5nM3a3M5gPwAAANtttttg+SdLSydLSydLSydLSyd" +
            "LSydLSydLSydLSydLSycwH4AAAAAFV3M5LSydLSydLSydLSydLSydLSydLSydLSydLSydEfPnz5tttttgAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAA973ve97wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAe973u973vve8AAAAAqu5nM3a3M5m7W5nM2lk6Wlk6Wl" +
            "k6Wlk6Wlk6WlmczdrczmA/AAAAADYPknS0snS0snS0snS0snS0snS0snS0snS0snS0snTbbfrYAAAAAqu5nM3a3M5lpZOlpZ" +
            "OlpZOlpZOlpZOlpczmbtbmczdrczmA/AAAA222222D5J0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJ0tLJzLAfsAB73ve973gA" +
            "xXczktLJ0tLJ0tLJ0SyydFLJ0tNzOZu1uZzN2tzOZu1uZzAfgAAAAAVVVVVVVVVVVVVQAAAAABsAAAAAAG222wAAAABVVVVV" +
            "VVVVVVVVAAAABtttttsAAAAAAUAAAAAAFVVAAAABbZb8+e9822222wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAHe970AAAAAG7XzmZnM5znN2qqqqqqqqqqq+fPnz58+e98AAG2222222tAAAAAFVAPeAAAAAbtQA" +
            "AAAANttt9+7bbAAAAN2qqqqqqqqqqqqgHvAAAAG22tAAAAAANtse8AAAAA3aqqqqqqqqqqqqAe8AAG2222222tAAAAAFVAPe" +
            "AAAAAbtQAAAAANttt9+7bbAAAAN2qqqqqqqqqqqqgHvAAAAG22tAAAAAAPnz3vgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAO973oAAAAAN2qqqqqqqqqqqqgHvAABtttttttrQAAAABVQD3gAAAAG7UAAAAADbb" +
            "bffu22wAAADdqqqqqqqqqqqqoB7wAAABttrQAAAAADbbHvAAAAAN2qqqqqqqqqqqqgHvAABtttttttrQAAAABVQD3gAAAAG7" +
            "UAAAAADbbbffu22wAAADdqqqqqqqqqqqqoB7wAAABttrQAAAAAD58974AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAADve96AAAAADdqqqqqqqqqqqqoB7wAAbbbbbbba0AAAAAVUA94AAAABu1AAAAAA222337tts" +
            "AAAA3aqqqqqqqqqqqqAe8AAAAbba0AAAAAA22x7wAAAADdqqqqqqqqqqqqoB7wAAbbbbbbba0AAAAAVUA94AAAABu1AAAAAA" +
            "222337ttsAAAA3aqqqqqqqqqqqqAe8AAAAbba0AAAAAA+fPe+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAA73vegAAAAA3aqqqqqqqqqqqqAe8AAG2222222tAAAAAFVAPeAAAAAbtQAAAAANttt9+7bbAAAAN2q" +
            "qqqqqqqqqqqgHvAAAAG22tAAAAAANtse8AAAAA3aqqqqqqqqqqqqAe8AAG2222222tAAAAAFVAPeAAAAAbtQAAAAANttt9+7" +
            "bbAAAAN2qqqqqqqqqqqqgHvAAAAG22tAAAAAAP1+v18+fgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4AAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAA/X6/X6AAAAAA3aqqqqqqqqqqqqAe8AANtttttttrQAAAACqoB7wAAAADdoAAAAADbbbffu22AAAAN2qq" +
            "qqqqqqqqqqgHvAAAANttrQAAAAADbYe8AAAAA3aqqqqqqqqqqqqAe8AANtttttttrQAAAACqoB7wAAAADdoAAAAADbbbffu2" +
            "2AAAAN2qqqqqqqqqqqqtAAAOAAbbbYAAAAAAP27pO7p3dP36SdO7u/30k6d3d/vpJ07u7/nSTp3d379JOnd3XpJ07u7+OknT" +
            "u7v26SdO7u/bpJ07u7+OknTu7v+9JOnd3fx0k6d3d/8XckU4UJCm+vBT"
        private const val CONCAT_BZ2 =
            "QlpoMTFBWSZTWVxauqMAACfV+QAQQAABACIiHAAYgACFAhQABIlAIABwUGjRoMgNApVQGEAMJoTMmJORMCZEwJiTqTInMmRM" +
            "xNSrwTYTQnwm5PxOhPpNyak1J3JsTcn8XckU4UJBcWrqjEJaaDkxQVkmU1mnlSwIAAA708kAEGAACAAuA5wAGIAABIgAIACQ" +
            "KYTTQGmIFKomh6mmI00zJgnkT8TQnompNSZEwTsTMmRNiak4E2JoTcTAmhME7icE6KuROSdE8E6J/F3JFOFCQp5UsCA="
        private const val PAX_TAR_BZ2 =
            "QlpoOTFBWSZTWR8HxUwADCB/////////////////////////////////////////////0AT+AAAAAAEmAAmAAJgAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAkwAEwABMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEmAAmAAJgAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAkwAEwABMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAqUpqmyGpkmQ81CNAnoan6BGeqaY" +
            "j1B6QI00YGkzSNHpDZAEbQm0AABpoyE0AAMjIwRhAMjIGTTI0wJgAAZAaBquNw+i+luOX4qxY2aZulVEpdVVgZyzESeMFfaq" +
            "YNil2mJVlKKqyMG+pg0uakuS7pQSeOHkBUk8kKeUFHlhTzApJ5oecHnhxOtXyScQknEpJ6AXESeiHpB6YeoHqh6wagNSHrh7" +
            "AeyHtB7Ye4GqDIB7oa97we+HwBUGHpGWDKIkZCrLhkQthswZgMkGzhmQuwzQZsNoDaQzSbGmlTQbTtYaThOA02ys+28K0b0Z" +
            "mTCokmtpSSTCokm26HQ9Fw9iaOkRoLmtFzVVVugbDVzdOC3OtNn3UKzI4qzXwKYFW7BcYi5s0sVbuFhXMNWpXLveAtV6rVi6" +
            "plN5DDVayVt6DDqsTeVVZ0Nb3sPlhvgXG+hTPBlokxtW/B0HzdKklAxYZT5wWMn88M+HABo7f0Q0eSwKxIhWJEOBCzoA40kQ" +
            "4IMHgwupEQtcIHChwwcOHDVyRDBC/4wkQxgXxIhbDRZQMANZr8eSIZcLYbKGVC2GEH1A4oLvRBhBnA0IcWHF34kQpz/TQ4wd" +
            "DboMNJiwvEyYZZJNMm617pPkDa0uE+JRylOPC6STpBfJJyIVJJkA0+dvcRW0QtBcDk8VtiYzWErVsWnSw3u8TOhlQuYaeyGM" +
            "WEzYY3LXg5YOXDPJJfJf8wGJtJJj7O0UGEF5dCwlvMhZsDNJWD4te+v5MwGuJiqWqyL27SvsYa6lSbBeuZDOBe6umIFExNUk" +
            "5oMPjvlWsvyukt2cmmrDnA29eyXl4lhjrTnQww0yc+FlK9KdAGkC7xlEs646EMdCrWA0odEHRhpgvQ1YLAYAc4GVC/DThgBm" +
            "Ax4dIGZDIhkgvwyAbUHShaDXQxoY8M2HTBsIWw+sHTh9cPsB9kPtB9sPuB90PvB98PwB+EPxB+MPyB+UOoDqQ/MH5w6oOrD9" +
            "AfpDrA60OuDrw7AP1B+sOxDsg7MO0D9gftD9wfvD+AfxD+QdqHbB/MP6B24dwH9Q/sH9w/wH+Q7kO6D/Qd2H+w7wO9D/gf9D" +
            "vg/8Hfh4AeCHhB4YeIH/w8UPGDxw8gPJDyg0vlh5geaHnB54egHoh6QemHqB6oesGoC8jUhT1w9gPZD2g9sPcCuQ1Qe6HvB7" +
            "4fAHwi7kinChID4Pipg="
        private const val GNU_TAR_BZ2 =
            "QlpoOTFBWSZTWXu1IqAADBl/////////////////////////////////////////////0AT+AAAAAAEmAAmAAJgAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAkwAEwABMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEmAAmAAJgAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAkwAEwABMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAqUohoaCamT00mJ6g1GZHlT1NP0T" +
            "JqPaSfpBDajIzQBNMmQbQj9RPRoNENGNR6AyAhiMIwAEYjCYEyaYmI00aGDQDI00GmgazQW2f+1tuBwWVmY3CpasklLSxqA2" +
            "q3iJPMCtmqYNbM4pVUoq1EVbzBqUrVUrFpSgk80POCqk88KaYKPQCnohST0g9MPUDDxSSffSTDST1QsIk9YNOGoD1w9gNSHs" +
            "hqg9oPbD3A90NWHvBrA2IPfDXvgD4Q+IKoV75OXSrGVCmURI2cL4MsGXDaAvgzAfNCzD5wZkMEM0GaTIpyqcBmsIOT4Pf9Jh" +
            "N+fQCpGPM3tEkmFQknz89nuWw9hmhpEcBZ1Is6lSm3h8nRVS3+qGczlhiKmRtmR+sFxWrUpuIWlcsbPgSvUzoYuysq1KUpY3" +
            "+5hiirYW26BbVLG6q2tLWwud1DHbsG7hvAWW8hTeg2aJM3V+uG970klAvAv/sBZX2+BvofZDicDfw4njMlUJEKhIh9wK/3Q4" +
            "gkQzwcCFnIiF3wQcGH3gu+Sww4bDtCRDBDGcQSIY4LwkQ2MMmF+FhoSRDKBefKDZgyYZMMIM+HDBw4YQZsM8GgDQXYkQpo+a" +
            "huwveUoLCS9DFJfhsCSaRNwtEzgwUsEyFGhCsknMDW0k40KqSbGGlzt1cZ8XWLCyF1mUvMSlTFpzMM7bJuQX4VkaXjg1ysmX" +
            "DG8d8y5H5A/KFfeEkxiYyzC6xiSZC2zFBtIXFoKyYGWCvWGXSoi7pJka1XESZUL1NapeVMTSFzXStx4a8lVLhyAYQYlLgUS6" +
            "xEk3kK+P+peYH472ra3yXI5EN8W8ltbJWx920QWA0ifnCullSmjDkgtZNdcmGOhb8oHKhywcuGkC3C5CsGyBog1wLsNKGSDK" +
            "BsIcwGzhkg2UMWGxBghzIXgXoY0MgGXDmg+QGAHNhzgc6H6A/SH6g54OfDoA/WH7A6EOiDow6QP2h+4OlDpg6cOoDqQ6oOrD" +
            "rA60OuDrw7AOxD94fwDsg7MO0D+IfyDtQ7YO3DuA/mHch3Qd2HeB3od8H9A78PAD+oeCH9g8IPDDxA/uHih4weOH+A/yHkB/" +
            "oPJD/Yf8Dyg/6H/g/+Hlh5geaHnB54aYNF6AeiHpB6YeoHqh6wacNQHrh7AakPZDVBcR7QU9sPcD3Q1Ye8FiQ1ge+HwB8IfE" +
            "Hxi7kinChIPdqRUA"

        private const val EXPECTED_MOZC_BYTES = 180000
        private const val EXPECTED_MOZC_SHA256 = "d3d724226c01068ac9ab452c26fc15c8cd6c06f67813f89b5cd7479c97829d74"
        private const val EXPECTED_LONG_PATH = "sherpa-onnx-nemo-parakeet/very_long_subdir_name_very_long_subdir_name_very_long_subdir_name_very_long_subdir_name_very_long_subdir_name_ignored_asset.txt"
    }

    private fun decodeB64(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun decompressAll(bz2Bytes: ByteArray, concat: Boolean = true, chunkSize: Int = 4096): ByteArray {
        BZip2InputStream(ByteArrayInputStream(bz2Bytes), decompressConcatenated = concat).use { ins ->
            val buf = ByteArray(chunkSize)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    @Test
    fun `empty bzip2 stream returns 0 bytes`() {
        val out = decompressAll(decodeB64(EMPTY_BZ2), concat = true)
        assertEquals(0, out.size)
        BZip2InputStream(ByteArrayInputStream(decodeB64(EMPTY_BZ2))).use { ins ->
            assertEquals(-1, ins.read())
        }
    }

    @Test
    fun `japanese UTF-8 stream decompresses identically via bulk and single-byte reads`() {
        val expected = ("あいうえお 日本語入力テスト JotInput 漢字変換 BZip2 解凍テスト!\n".repeat(5))
            .toByteArray(Charsets.UTF_8)
        val bz2 = decodeB64(JP_BZ2)

        assertArrayEquals(expected, decompressAll(bz2, chunkSize = 17))

        BZip2InputStream(ByteArrayInputStream(bz2)).use { ins ->
            val singleReadOut = java.io.ByteArrayOutputStream()
            while (true) {
                val b = ins.read()
                if (b < 0) break
                singleReadOut.write(b)
            }
            assertArrayEquals(expected, singleReadOut.toByteArray())
        }
    }

    @Test
    fun `RLE2 boundary runs of 4, 5, 259, 260, and 1000 bytes decode accurately`() {
        val expected = ByteArray(4) { 'A'.code.toByte() } +
            ByteArray(5) { 'B'.code.toByte() } +
            ByteArray(259) { 'C'.code.toByte() } +
            ByteArray(260) { 'D'.code.toByte() } +
            ByteArray(1000) { 'E'.code.toByte() } +
            ByteArray(300) { 0 } +
            ByteArray(300) { 0xFF.toByte() }

        val actual = decompressAll(decodeB64(RLE_BZ2), chunkSize = 7)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `high entropy random payloads decode across BZh1 and BZh9`() {
        val expected = decodeB64(RAND_RAW)
        assertArrayEquals(expected, decompressAll(decodeB64(RAND_BZ2_1), chunkSize = 64))
        assertArrayEquals(expected, decompressAll(decodeB64(RAND_BZ2_9), chunkSize = 128))
    }

    @Test
    fun `multi-block BZh1 dictionary stream verifies combined CRC rotation`() {
        val actual = decompressAll(decodeB64(MULTIBLOCK_MOZC_BZ2_1), chunkSize = 8192)
        assertEquals(EXPECTED_MOZC_BYTES, actual.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(actual)
            .joinToString("") { "%02x".format(it) }
        assertEquals(EXPECTED_MOZC_SHA256, sha)
    }

    @Test
    fun `concatenated bzip2 streams obey decompressConcatenated flag`() {
        val part1 = ("First stream 日本語データ\n".repeat(20)).toByteArray(Charsets.UTF_8)
        val part2 = ("Second stream concatenated データ!\n".repeat(20)).toByteArray(Charsets.UTF_8)
        val concatBytes = decodeB64(CONCAT_BZ2)

        assertArrayEquals(part1 + part2, decompressAll(concatBytes, concat = true))
        assertArrayEquals(part1, decompressAll(concatBytes, concat = false))
    }

    @Test
    fun `corrupt CRC and truncated streams throw IOException`() {
        val valid = decodeB64(JP_BZ2)
        val badCombinedCrc = valid.copyOf()
        badCombinedCrc[badCombinedCrc.lastIndex] = (badCombinedCrc.last().toInt() xor 0xFF).toByte()
        try {
            decompressAll(badCombinedCrc)
            fail("Expected IOException for corrupt combined CRC")
        } catch (_: IOException) {
            // expected
        }

        val badBlock = valid.copyOf()
        badBlock[20] = (badBlock[20].toInt() xor 0x55).toByte()
        try {
            decompressAll(badBlock)
            fail("Expected IOException for corrupt block payload/CRC")
        } catch (_: IOException) {
            // expected
        }

        val truncated = valid.copyOf(16)
        try {
            decompressAll(truncated)
            fail("Expected IOException for truncated stream")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `PAX and GNU tar-bz2 archives extract entries after skipping directories, long paths, and unread files`() {
        val expectedTokens = ("<blk>\n a\n b\n c\n".repeat(30)).toByteArray(Charsets.UTF_8)
        val expectedModel = ByteArray(4099) { i -> ((i * 31) and 0xFF).toByte() }

        for (archiveB64 in listOf(PAX_TAR_BZ2, GNU_TAR_BZ2)) {
            val seenEntries = mutableListOf<String>()
            var actualTokens: ByteArray? = null
            var actualModel: ByteArray? = null

            TarInputStream(
                BZip2InputStream(ByteArrayInputStream(decodeB64(archiveB64)), decompressConcatenated = true)
            ).use { tar ->
                while (true) {
                    val entry = tar.nextEntry() ?: break
                    seenEntries.add(entry.name)
                    if (entry.name.endsWith("README.md")) {
                        val partial = ByteArray(10)
                        assertEquals(10, tar.read(partial))
                    } else if (entry.name.endsWith("/tokens.txt")) {
                        actualTokens = tar.readBytes()
                    } else if (entry.name.endsWith("/model.int8.onnx")) {
                        actualModel = tar.readBytes()
                    }
                }
                assertNull(tar.nextEntry())
            }

            assertTrue("Expected directory entry", seenEntries.any { it.endsWith("sherpa-onnx-nemo-parakeet/") || it == "sherpa-onnx-nemo-parakeet" })
            assertTrue("Expected long path entry >100 chars: $seenEntries", seenEntries.contains(EXPECTED_LONG_PATH))
            assertNotNull(actualTokens)
            assertNotNull(actualModel)
            assertArrayEquals(expectedTokens, actualTokens)
            assertArrayEquals(expectedModel, actualModel)
        }
    }
}
