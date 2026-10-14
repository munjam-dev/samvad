import { Fragment } from 'react';
import Tab from '../ui/tab/Tab';
import TabList from '../ui/tab/TabList';

const FaqTabList = () => {
  return (
    <Fragment>
      <TabList variant="desktop">
        <Tab index={0} variant="desktop">
          General
        </Tab>
        <Tab index={1} variant="desktop">
          Features
        </Tab>
        <Tab index={2} variant="desktop">
          Privacy &amp; safety
        </Tab>
        <Tab index={3} variant="desktop">
          Account
        </Tab>
      </TabList>
      <TabList variant="mobile">
        <Tab index={0} variant="mobile">
          General
        </Tab>
        <Tab index={1} variant="mobile">
          Features
        </Tab>
        <Tab index={2} variant="mobile">
          Privacy &amp; safety
        </Tab>
        <Tab index={3} variant="mobile" className="text-nowrap">
          Account
        </Tab>
      </TabList>
    </Fragment>
  );
};

export default FaqTabList;
