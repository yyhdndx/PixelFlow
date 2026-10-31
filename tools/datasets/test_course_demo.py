import struct
import unittest
from prepare_demo_feed import dimensions
from publish_course_demo import business_name, select_rows
from wukong_corpus import normalize


class CourseDemoTests(unittest.TestCase):
    def test_png_gif_jpeg_dimensions(self):
        png=b'\x89PNG\r\n\x1a\n'+b'\x00\x00\x00\x0dIHDR'+struct.pack('>II',640,480)
        gif=b'GIF89a'+struct.pack('<HH',320,240)
        jpeg=b'\xff\xd8\xff\xe0\x00\x04AB\xff\xc2\x00\x0b\x08'+struct.pack('>HH',480,640)+b'\x01\x01\x11\x00'
        self.assertEqual((640,480),dimensions(png))
        self.assertEqual((320,240),dimensions(gif))
        self.assertEqual((640,480),dimensions(jpeg))

    def test_webp_variants(self):
        header=b'RIFF'+b'\x00'*4+b'WEBP'
        self.assertEqual((640,480),dimensions(header+b'VP8X'+b'\x00'*8+(639).to_bytes(3,'little')+(479).to_bytes(3,'little')))
        self.assertEqual((640,480),dimensions(header+b'VP8 '+b'\x00'*7+b'\x9d\x01\x2a'+struct.pack('<HH',640,480)))
        self.assertEqual((640,480),dimensions(header+b'VP8L'+b'\x00'*4+b'\x2f'+(639+(479<<14)).to_bytes(4,'little')))

    def test_truncated_and_invalid_headers(self):
        for data in [b'',b'not an image',b'\xff\xd8\xff',b'\xff\xd8\xff\xe0\x00\x00',b'\xff\xd8\xff\xda',b'RIFF']:
            self.assertIsNone(dimensions(data))

    def test_only_checked_valid_dimensions_are_published(self):
        row=normalize({'caption':'旅行风景样本记录','url':'https://example.com/a.jpg'},'wukong_release/wukong_100m_0.csv',1)
        key=row['source_record_id']
        manifest={'records_sha256':'a'*64}
        checks={'records':{key:{'resource_status':'REACHABLE'}}}
        dims={'records_sha256':'a'*64,'records':{key:{'width':640,'height':480}}}
        selected=select_rows([row],manifest,checks,dims)
        self.assertEqual(640,selected[0][1]['width'])
        for bad in [None,True,0,-1,16385,'640']:
            dims['records'][key]['width']=bad
            with self.assertRaises(ValueError): select_rows([row],manifest,checks,dims)
        dims['records'][key]['width']=640
        checks['records'][key]['resource_status']='UNREACHABLE'
        with self.assertRaises(ValueError): select_rows([row],manifest,checks,dims)

    def test_target_allowlist(self):
        for name in ['mysql','pixelflow_analytics','pixelflow_app;DROP DATABASE mysql','pixelflow_demo_test_']:
            with self.assertRaises(ValueError): business_name(name)
        self.assertEqual('`pixelflow_app`',business_name('pixelflow_app'))


if __name__=='__main__': unittest.main()
