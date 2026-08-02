package libcore

import (
	"bytes"
	"fmt"
	"os"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type geositeReader struct {
	reader *geosites.Reader
}

func openGeoSite(path string) (*geositeReader, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	reader, _, err := geosites.NewReader(bytes.NewReader(content))
	if err != nil {
		return nil, err
	}
	return &geositeReader{reader: reader}, nil
}

func (g *geositeReader) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.reader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule
	defaultRule := geosites.Compile(sourceSet)
	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}
